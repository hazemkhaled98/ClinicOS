package com.clinicos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InventoryAnalyticsService {

    record Dashboard(int itemsBelowAlert, int pendingApprovals, int openOrders,
            BigDecimal trayValue, BigDecimal storeValue) {
    }

    record ProfitRow(String procedureName, BigDecimal revenue, BigDecimal materialCost,
            BigDecimal laborCost, BigDecimal doctorFee, BigDecimal margin, long caseCount) {
    }

    record ConsumptionRow(String itemName, BigDecimal consumed, BigDecimal onHand,
            BigDecimal valueOnHand, BigDecimal turnover) {
    }

    record WasteRow(String month, String itemName, BigDecimal quantity, BigDecimal value) {
    }

    record DoctorRow(String doctorName, long caseCount, BigDecimal revenue, BigDecimal materialCost,
            BigDecimal laborCost, BigDecimal doctorFee, BigDecimal margin) {
    }

    record SupplierRow(String supplierName, long orderCount, BigDecimal spend,
            BigDecimal averageUnitCost, BigDecimal averageLeadDays, BigDecimal returnRate) {
    }

    record ItemPriceRow(String itemName, String supplierName, java.time.OffsetDateTime purchasedAt,
            BigDecimal unitCost) {
    }

    Dashboard dashboard(java.util.UUID clinicId);
    List<ProfitRow> profit(java.util.UUID clinicId, LocalDate from, LocalDate to);
    List<ConsumptionRow> consumption(java.util.UUID clinicId, LocalDate from, LocalDate to);
    List<WasteRow> waste(java.util.UUID clinicId, LocalDate from, LocalDate to);
    List<DoctorRow> doctors(java.util.UUID clinicId, LocalDate from, LocalDate to);
    List<SupplierRow> suppliers(java.util.UUID clinicId, LocalDate from, LocalDate to);
    List<ItemPriceRow> itemPrices(java.util.UUID clinicId, LocalDate from, LocalDate to);
}
