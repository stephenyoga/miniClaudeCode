package com.shop.web;

import com.shop.service.ReportService;
import com.shop.util.PriceFormatter;

/** 报表端点。当前只有收入总额与文本版客户报表（CSV 导出待 issue 实现） */
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    public void printRevenue() {
        System.out.println("已支付收入合计: " + PriceFormatter.formatCents(reportService.paidRevenue()));
    }

    public void printCustomerRevenue() {
        System.out.println("== 按客户消费汇总（文本） ==");
        for (ReportService.CustomerReportRow row : reportService.customerRevenue()) {
            System.out.println(row.customerName() + " (" + row.customerId() + ") "
                    + PriceFormatter.formatCents(row.spentCents()));
        }
    }
}
