package com.autolog.server.domain.order.controller;

import com.autolog.server.domain.order.dto.request.MarketBuyRequest;
import com.autolog.server.domain.order.dto.response.MarketOrderResponse;
import com.autolog.server.domain.order.facade.MarketOrderFacade;
import com.autolog.server.global.web.CurrentUserId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

	private final MarketOrderFacade marketOrderFacade;

	/** 시장가 매수 (즉시 환전, KRW → 외화). */
	@PostMapping("/market")
	public ResponseEntity<MarketOrderResponse> marketBuy(@CurrentUserId Long userId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody MarketBuyRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(marketOrderFacade.buy(userId, idempotencyKey, request));
	}
}
