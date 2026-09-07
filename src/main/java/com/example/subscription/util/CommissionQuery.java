package com.example.subscription.util;

import com.example.subscription.model.CommissionRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared filtering/pagination for the admin and super-admin "list
 * commissions" endpoints, so the frontend's {status, period, page, limit}
 * query params behave the same way in both places.
 *
 * Commissions don't have their own PENDING/FAILED lifecycle (a
 * CommissionRecord is only ever created once the underlying payment already
 * succeeded) - the only real state is whether it has been paid out to the
 * admin yet. So the "status" filter maps onto paidOut:
 *   status=PAID    -> paidOut == true
 *   status=PENDING -> paidOut == false
 * Any other/unrecognized status value is ignored (no filtering applied).
 */
public final class CommissionQuery {

    private CommissionQuery() {
    }

    public static Map<String, Object> paginate(List<CommissionRecord> all, String status, String period,
                                                 Integer page, Integer limit) {
        List<CommissionRecord> filtered = all;

        if (status != null && !status.isBlank()) {
            if ("PAID".equalsIgnoreCase(status)) {
                filtered = filtered.stream().filter(CommissionRecord::isPaidOut).collect(Collectors.toList());
            } else if ("PENDING".equalsIgnoreCase(status)) {
                filtered = filtered.stream().filter(c -> !c.isPaidOut()).collect(Collectors.toList());
            }
            // FAILED (or anything else) has no equivalent on a CommissionRecord - ignored.
        }

        if (period != null && !period.isBlank()) {
            LocalDateTime since = periodStart(period);
            if (since != null) {
                filtered = filtered.stream()
                        .filter(c -> c.getCreatedAt() != null && !c.getCreatedAt().isBefore(since))
                        .collect(Collectors.toList());
            }
        }

        // Newest first.
        filtered = filtered.stream()
                .sorted((a, b) -> {
                    LocalDateTime ca = a.getCreatedAt();
                    LocalDateTime cb = b.getCreatedAt();
                    if (ca == null && cb == null) return 0;
                    if (ca == null) return 1;
                    if (cb == null) return -1;
                    return cb.compareTo(ca);
                })
                .collect(Collectors.toList());

        int pageNum = (page == null || page < 1) ? 1 : page;
        int pageSize = (limit == null || limit < 1) ? 20 : Math.min(limit, 200);

        int total = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        if (pageNum > totalPages) pageNum = totalPages;

        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<CommissionRecord> pageItems = filtered.subList(fromIndex, toIndex);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", pageNum);
        meta.put("totalPages", totalPages);
        meta.put("total", total);
        meta.put("limit", pageSize);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", pageItems);
        result.put("meta", meta);
        return result;
    }

    private static LocalDateTime periodStart(String period) {
        LocalDate today = LocalDate.now();
        switch (period.toLowerCase()) {
            case "today":
                return today.atStartOfDay();
            case "week":
                return today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay();
            case "month":
                return today.withDayOfMonth(1).atStartOfDay();
            case "year":
                return today.withDayOfYear(1).atStartOfDay();
            default:
                return null;
        }
    }
}
