package com.starkgroup.vms.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.starkgroup.vms.common.exception.BadRequestException;

class PageRequestsTest {

    private static final Map<String, String> ALLOWED = Map.of("vehicleNumber", "vehicleNumber",
            "createdAt", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void tc11_sizeAboveMaximumIsCapped() {
        PageRequest request = PageRequests.of(2, 500, null, ALLOWED, DEFAULT_SORT);

        assertThat(request.getPageSize()).isEqualTo(PageRequests.MAX_PAGE_SIZE);
        assertThat(request.getPageNumber()).isEqualTo(2);
    }

    @Test
    void pageWhoseOffsetDoesNotFitTheDatabaseLimitIsBadRequest() {
        assertThatThrownBy(() -> PageRequests.of(Integer.MAX_VALUE, 100, null, ALLOWED, DEFAULT_SORT))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("out of range");
        assertThat(PageRequests.of(Integer.MAX_VALUE / 100, 100, null, ALLOWED, DEFAULT_SORT).getOffset())
                .isLessThanOrEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void blankSortUsesDefault() {
        assertThat(PageRequests.of(0, 20, " ", ALLOWED, DEFAULT_SORT).getSort()).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void sortDirectionIsOptionalAndCaseInsensitive() {
        assertThat(PageRequests.parseSort("vehicleNumber", ALLOWED, DEFAULT_SORT))
                .containsExactly(Sort.Order.asc("vehicleNumber"));
        assertThat(PageRequests.parseSort("vehicleNumber,DESC", ALLOWED, DEFAULT_SORT))
                .containsExactly(Sort.Order.desc("vehicleNumber"));
    }

    @Test
    void unknownFieldBadDirectionAndBadShapeAreRejected() {
        assertThatThrownBy(() -> PageRequests.parseSort("passwordHash,asc", ALLOWED, DEFAULT_SORT))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown sort field 'passwordHash'. Allowed fields: createdAt, vehicleNumber.");
        assertThatThrownBy(() -> PageRequests.parseSort("createdAt,sideways", ALLOWED, DEFAULT_SORT))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageRequests.parseSort("createdAt,asc,extra", ALLOWED, DEFAULT_SORT))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void likePatternEscapesWildcards() {
        assertThat(LikePatterns.containsIgnoreCase("Dhk_1%")).isEqualTo("%dhk\\_1\\%%");
    }
}
