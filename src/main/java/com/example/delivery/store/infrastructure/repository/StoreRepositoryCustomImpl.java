package com.example.delivery.store.infrastructure.repository;

import com.example.delivery.area.domain.entity.QAreaEntity;
import com.example.delivery.store.domain.entity.QStoreEntity;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StoreRepositoryCustomImpl implements StoreRepositoryCustom {

    private static final QStoreEntity STORE = QStoreEntity.storeEntity;
    private static final QAreaEntity AREA = QAreaEntity.areaEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<StoreEntity> search(String keyword, UUID categoryId, UUID areaId, Pageable pageable) {

        BooleanExpression[] conditions = new BooleanExpression[]{
                notDeleted(),
                notHidden(),
                areaIsActive(),
                nameContainsIgnoreCase(keyword),
                categoryIdEq(categoryId),
                areaIdEq(areaId)
        };

        List<StoreEntity> content = queryFactory
                .selectFrom(STORE)
                .join(AREA).on(STORE.areaId.eq(AREA.id))
                .where(conditions)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long totalCount = queryFactory
                .select(STORE.count())
                .from(STORE)
                .join(AREA).on(STORE.areaId.eq(AREA.id))
                .where(conditions)
                .fetchOne();

        long total = totalCount != null ? totalCount : 0L;

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * 조건 조립 (null 이면 조건 자체를 제외)
     */
    private BooleanExpression notDeleted() {
        return STORE.deletedAt.isNull();
    }

    private BooleanExpression notHidden() {
        return STORE.isHidden.isFalse();
    }

    private BooleanExpression areaIsActive() {
        return AREA.isActive.isTrue();
    }

    private BooleanExpression nameContainsIgnoreCase(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return STORE.name.containsIgnoreCase(keyword.trim());
    }

    private BooleanExpression categoryIdEq(UUID categoryId) {
        return categoryId == null ? null : STORE.categoryId.eq(categoryId);
    }

    private BooleanExpression areaIdEq(UUID areaId) {
        return areaId == null ? null : STORE.areaId.eq(areaId);
    }

    /**
     * 정렬
     * Pageable 의 Sort 를 QueryDSL OrderSpecifier 로 변환
     * 지원 속성: createdAt, updatedAt, name, averageRating
     * 지원하지 않는 속성은 무시하고, 결과가 비면 createdAt DESC 를 기본 적용
     */
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();

        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = resolvePath(order.getProperty());
            if (path == null) {
                continue;
            }
            Order direction = order.isAscending() ? Order.ASC : Order.DESC;
            specifiers.add(new OrderSpecifier<>(direction, path));
        }

        if (specifiers.isEmpty()) {
            specifiers.add(STORE.createdAt.desc());
        }

        return specifiers.toArray(new OrderSpecifier[0]);
    }

    private ComparableExpressionBase<?> resolvePath(String property) {
        return switch (property) {
            case "createdAt" -> STORE.createdAt;
            case "updatedAt" -> STORE.updatedAt;
            case "name" -> STORE.name;
            case "averageRating" -> STORE.averageRating;
            default -> null;
        };
    }
}
