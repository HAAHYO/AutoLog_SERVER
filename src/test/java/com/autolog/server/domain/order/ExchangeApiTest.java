package com.autolog.server.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.ledger.service.LedgerReconciliationService;
import com.autolog.server.domain.order.repository.FxOrderRepository;
import com.autolog.server.global.money.CurrencyCode;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 충전·시장가 매수·멱등성 키 API 통합 테스트 (H2).
 * 트랜잭션 전파(REQUIRES_NEW, NOT_SUPPORTED)를 실제로 타야 하므로 테스트에 @Transactional을 붙이지 않고,
 * 테스트마다 다른 userId를 써서 서로 간섭하지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExchangeApiTest {

	private static final AtomicLong USER_SEQ = new AtomicLong(1000);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private FxOrderRepository fxOrderRepository;

	@Autowired
	private LedgerReconciliationService reconciliationService;

	@Test
	void 충전하면_금액과_잔액이_문자열로_응답되고_계좌가_개설된다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		deposit(userId, "dep-1", "50000")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency").value("KRW"))
				.andExpect(jsonPath("$.amount").value("50000"))
				.andExpect(jsonPath("$.balance").value("50000"));

		mockMvc.perform(get("/api/v1/accounts").header("X-User-Id", userId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts[0].currency").value("KRW"))
				.andExpect(jsonPath("$.accounts[0].balance").value("50000"));
	}

	@Test
	void 같은_키와_같은_본문으로_다시_충전하면_처음_응답을_돌려주고_한_번만_입금된다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		String first = deposit(userId, "dep-1", "50000").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String second = deposit(userId, "dep-1", "50000").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(krwBalance(userId)).isEqualTo("50000");
	}

	@Test
	void 같은_금액을_다른_표기로_다시_보내도_같은_요청으로_본다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		String first = deposit(userId, "dep-1", "1000").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String second = deposit(userId, "dep-1", "1000.00").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(krwBalance(userId)).isEqualTo("1000");
	}

	@Test
	void 잘못된_키로_요청하면_계좌를_만들지_않는다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		marketBuy(userId, "bad key!", "USD", "10000").andExpect(status().isBadRequest());

		assertThat(accountRepository.findAllByUserIdOrderByIdAsc(userId)).isEmpty();
	}

	@Test
	void 같은_키로_다른_본문을_보내면_422로_거부한다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();
		deposit(userId, "dep-1", "50000").andExpect(status().isCreated());

		deposit(userId, "dep-1", "70000")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("I003"));
		assertThat(krwBalance(userId)).isEqualTo("50000");
	}

	@Test
	void 같은_키라도_사용자가_다르면_별개의_요청이다() throws Exception {
		long userA = USER_SEQ.incrementAndGet();
		long userB = USER_SEQ.incrementAndGet();

		deposit(userA, "same-key", "1000").andExpect(status().isCreated());
		deposit(userB, "same-key", "2000").andExpect(status().isCreated());

		assertThat(krwBalance(userB)).isEqualTo("2000");
	}

	@Test
	void 필수_헤더가_없거나_금액이_잘못되면_400이다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		mockMvc.perform(post("/api/v1/accounts/deposits").header("X-User-Id", userId)
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"1000\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("I001"));
		mockMvc.perform(post("/api/v1/accounts/deposits").header("Idempotency-Key", "k1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"1000\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("U001"));
		deposit(userId, "k2", "1000.5")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("C001"));
		deposit(userId, "bad key!", "1000")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("I001"));
	}

	@Test
	void 시장가로_USD를_사면_원장_5줄이_균형을_이루고_잔액과_대사가_일치한다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();
		deposit(userId, "dep-1", "200000").andExpect(status().isCreated());

		marketBuy(userId, "buy-1", "USD", "136500")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.orderType").value("MARKET"))
				.andExpect(jsonPath("$.krwAmount").value("136500"))
				.andExpect(jsonPath("$.foreignAmount").value("99.37"))
				.andExpect(jsonPath("$.baseRate").value("1350.00"))
				.andExpect(jsonPath("$.appliedRate").value("1373.62500000"))
				.andExpect(jsonPath("$.rateSource").value("CONFIGURED"))
				.andExpect(jsonPath("$.krwBalance").value("63500"))
				.andExpect(jsonPath("$.foreignBalance").value("99.37"));

		Long krwId = accountRepository.findByUserIdAndCurrencyAndType(userId, CurrencyCode.KRW, AccountType.USER).orElseThrow().getId();
		Long usdId = accountRepository.findByUserIdAndCurrencyAndType(userId, CurrencyCode.USD, AccountType.USER).orElseThrow().getId();
		assertThat(reconciliationService.reconcileAccount(krwId).matched()).isTrue();
		assertThat(reconciliationService.reconcileAccount(usdId).matched()).isTrue();
		assertThat(reconciliationService.verifyCurrencyBalances()).allSatisfy(b -> assertThat(b.balanced()).isTrue());
	}

	@Test
	void 같은_키로_다시_매수해도_한_번만_체결된다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();
		deposit(userId, "dep-1", "300000").andExpect(status().isCreated());
		long ordersBefore = fxOrderRepository.count();

		String first = marketBuy(userId, "buy-1", "USD", "136500").andReturn().getResponse().getContentAsString();
		String second = marketBuy(userId, "buy-1", "USD", "136500").andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(fxOrderRepository.count()).isEqualTo(ordersBefore + 1);
		assertThat(krwBalance(userId)).isEqualTo("163500");
	}

	@Test
	void 잔액이_부족하면_422이고_주문과_잔액이_남지_않으며_충전_후_같은_키로_재시도할_수_있다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();
		deposit(userId, "dep-1", "100000").andExpect(status().isCreated());
		long ordersBefore = fxOrderRepository.count();

		marketBuy(userId, "buy-1", "USD", "136500")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("A002"));
		assertThat(fxOrderRepository.count()).isEqualTo(ordersBefore);
		assertThat(krwBalance(userId)).isEqualTo("100000");

		deposit(userId, "dep-2", "100000").andExpect(status().isCreated());
		marketBuy(userId, "buy-1", "USD", "136500").andExpect(status().isCreated());
	}

	@Test
	void KRW나_알_수_없는_통화는_살_수_없다() throws Exception {
		long userId = USER_SEQ.incrementAndGet();

		marketBuy(userId, "buy-1", "KRW", "10000")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("O001"));
		marketBuy(userId, "buy-2", "XYZ", "10000")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("C001"));
	}

	@Test
	void 전체_원장은_통화별로_항상_균형이다() {
		assertThat(reconciliationService.verifyCurrencyBalances()).allSatisfy(b -> assertThat(b.balanced()).isTrue());
	}

	private ResultActions deposit(long userId, String key, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/accounts/deposits")
				.header("X-User-Id", userId)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":\"" + amount + "\"}"));
	}

	private ResultActions marketBuy(long userId, String key, String currency, String krwAmount) throws Exception {
		return mockMvc.perform(post("/api/v1/orders/market")
				.header("X-User-Id", userId)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetCurrency\":\"" + currency + "\",\"krwAmount\":\"" + krwAmount + "\"}"));
	}

	private String krwBalance(long userId) {
		return accountRepository.findByUserIdAndCurrencyAndType(userId, CurrencyCode.KRW, AccountType.USER)
				.orElseThrow().getBalance().stripTrailingZeros().toPlainString();
	}
}
