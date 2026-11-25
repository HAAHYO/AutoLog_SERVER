package com.autolog.server.domain.order.repository;

import com.autolog.server.domain.order.entity.FxOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxOrderRepository extends JpaRepository<FxOrder, Long> {
}
