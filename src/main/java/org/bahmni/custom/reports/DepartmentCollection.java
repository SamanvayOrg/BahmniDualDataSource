package org.bahmni.custom.reports;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bahmni.custom.AbstractBahmniReport;
import org.bahmni.custom.Util;
import org.bahmni.custom.Utils;
import org.bahmni.custom.data.AccountVoucher;
import org.bahmni.custom.data.AccountVoucherLine;
import org.bahmni.custom.data.BahmniReportObject;
import org.bahmni.custom.data.ReportLine;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Created by sandeepe on 11/03/16.
 */
public class DepartmentCollection  extends AbstractBahmniReport {

    private List<AccountVoucherLine> opdDeptMissingAVLs = new ArrayList<AccountVoucherLine>();
    private List<AccountVoucherLine> opdDeptMissingSOs = new ArrayList<AccountVoucherLine>();

    public Map<String, ReportLine> getSaleOrdersForTodayWithDepartment() {

/*
        --------------------
    Get All Vouchers for today...

    Find out all SO for Vouchers

    Put ip/op to SO

             ---------------
  */
        String accVouchersPay = "SELECT av.id,av.amount,av.balance_amount,av.balance_before_pay,av.number," +
                "  avl.id,avl.amount,avl.amount_unreconciled,avl.amount_original,avl.type," +
                " so.name,so.care_setting,so.id,rpa.\"x_Is_Tribal\" from account_voucher av" +
                " LEFT JOIN account_voucher_line avl on avl.voucher_id=av.id" +
                " LEFT JOIN account_move_line aml on avl.move_line_id = aml.id" +
                " LEFT JOIN res_partner_attributes rpa on rpa.partner_id=av.partner_id" +
                " LEFT JOIN sale_order so on so.name = aml.ref" +
                " where av.amount>0 and av.state='posted' " +
                " and cast(av.date_string as DATE) between '2016-04-01' and '2016-04-13'";

        String accVouchersRefund = "SELECT av.id,av.amount,av.balance_amount,av.balance_before_pay,av.number," +
                "  avl.id,avl.amount,avl.amount_unreconciled,avl.amount_original,avl.type," +
                " so.name,so.care_setting,so.id,rpa.\"x_Is_Tribal\" from account_voucher av" +
                " LEFT JOIN account_voucher_line avl on avl.voucher_id=av.id" +
                "  LEFT JOIN account_move_line aml on avl.move_line_id = aml.id" +
                " LEFT JOIN res_partner_attributes rpa on rpa.partner_id=av.partner_id" +
                "  LEFT JOIN sale_order so on so.name = aml.ref" +
                " where av.amount<0 and av.state='posted' " +
                " and cast(av.date_string as DATE) between '2016-04-01' and '2016-04-13'";
        List<AccountVoucher> accountVouchersPay = parseAccountVouchers(accVouchersPay);
        List<AccountVoucher> accountVouchersRefund = parseAccountVouchers(accVouchersRefund);

        allocatePayedAmount(accountVouchersPay);
        allocatePayedAmount(accountVouchersRefund);

        printAmountAllcation(accountVouchersPay, true);
        printAmountAllcation(accountVouchersRefund, false);

        List<AccountVoucherLine> opdAVLPayed = getSaleOrdersForPaymentForCareSetting(accountVouchersPay, "opd", true);
        List<AccountVoucherLine> ipdAVLPayed = getSaleOrdersForPaymentForCareSetting(accountVouchersPay, "ipd", true);

        List<AccountVoucherLine> opdAVLRefund = getSaleOrdersForPaymentForCareSetting(accountVouchersRefund, "opd", false);
        List<AccountVoucherLine> ipdAVLRefund = getSaleOrdersForPaymentForCareSetting(accountVouchersRefund, "ipd", false);

        String commonCatIdQuery = "SELECT id from product_category where name = 'Common' limit 1";
        Integer commonsId = getErpJdbcTemplate().queryForObject(commonCatIdQuery, Integer.class);
        final Map<String, List<Integer>> deptCatMap = getDeptCatListMap();

        String idcsv = getCSVForSOID(opdAVLPayed);
        Map<Integer, Integer> catOpdAVLPaidMap = getCatAVLMap(commonsId, idcsv);
        addCategoryToAVL(opdAVLPayed, catOpdAVLPaidMap);
        final Map<String, List<AccountVoucherLine>> departmentOpdPaymentAVL = getSaleOrdersForDepartments(opdAVLPayed, deptCatMap, false);

        String idcsvopdrefund = getCSVForSOID(opdAVLRefund);
        Map<Integer, Integer> catOpdAVLRefundMap = getCatAVLMap(commonsId, idcsvopdrefund);
        addCategoryToAVL(opdAVLRefund, catOpdAVLRefundMap);
        final Map<String, List<AccountVoucherLine>> departmentOpdRefundAVL = getSaleOrdersForDepartments(opdAVLRefund, deptCatMap, false);


        //We might have AVL with same SO repeating in case if payment is done multiple times within the period

        //Todays SO both ipd/opd

        //Todays Vouchers
        //Get SO for voucher..

        //Both today and previous SOs

        //dr with inv ref (out_refund), actual refunds
        //dr without inv, against advance amount..
        //If a voucher has both dr and cr, then advance amount comes

        //balance <0 :- Advance  , possible if overpaid as well
        // if, balance_before_pay, amount,balance_amount


        //Over pay, balance_amount < balance >0, amount>0, balance_amount<0
        // Extra amount should go to advance

        //Just advance, balance=0, balance_amount<0, amount>0
        //if amount>0 and balance=0, full paid

        //Consider only if amount > 0


        //If pay 3 SO with a advance+current amount,
        //get balance amount after deduction advance (dr with BNK reference),
        //If an SO is in range, allocate money to that, rest amount allocate equally to others..
        //Give preference to IPD
        Map<String, ReportLine> deptPayedAmt = new HashMap<String,ReportLine>();
        for (Map.Entry<String, List<AccountVoucherLine>> dept : departmentOpdPaymentAVL.entrySet()) {
            ReportLine amt = new ReportLine();
            amt.setPaidAmountNonTribal(getAmountFromAVL(dept.getValue(), false));
            amt.setPaidAmountTribal(getAmountFromAVL(dept.getValue(), true));
            deptPayedAmt.put(dept.getKey(), amt);

        }
        for (Map.Entry<String, List<AccountVoucherLine>> dept : departmentOpdRefundAVL.entrySet()) {
            ReportLine reportLine = deptPayedAmt.get(dept.getKey());
            if (reportLine == null) {
                reportLine = new ReportLine();
                deptPayedAmt.put(dept.getKey(), reportLine);
            }
            reportLine.setRefundAmountNonTribal(getAmountFromAVL(dept.getValue(), false));
            reportLine.setRefundAmountTribal(getAmountFromAVL(dept.getValue(), true));
        }

        {
            ReportLine line = new ReportLine();
            line.setRefundAmountTribal(getAmountFromAVL(ipdAVLRefund, true));
            line.setRefundAmountNonTribal(getAmountFromAVL(ipdAVLRefund, false));
            line.setPaidAmountTribal(getAmountFromAVL(ipdAVLPayed, true));
            line.setPaidAmountNonTribal(getAmountFromAVL(ipdAVLPayed, false));
            deptPayedAmt.put("IP", line);
        }

        {
            double allocationForNeitherOpNorIPCrTribal = getAllocationForNeitherOpNorIP(accountVouchersPay, true, true);
            double allocationForNeitherOpNorIPCrNonTribal = getAllocationForNeitherOpNorIP(accountVouchersPay, true, false);
            double allocationForNeitherOpNorIPDrTribal = getAllocationForNeitherOpNorIP(accountVouchersRefund, false, true);
            double allocationForNeitherOpNorIPDrNonTribal = getAllocationForNeitherOpNorIP(accountVouchersRefund, false, false);

            double tribalMissingIPDDeptPayment = getMissingIPDDeptPayment("cr", true);
            double nonTribalMissingIPDDeptPayment = getMissingIPDDeptPayment("cr", false);
            double tribalMissingIPDDeptRefund = getMissingIPDDeptPayment("dr", true);
            double nonTribalMissingIPDDeptRefund = getMissingIPDDeptPayment("dr", false);

            ReportLine lineO = new ReportLine();
            lineO.setPaidAmountTribal(tribalMissingIPDDeptPayment + getOtherPaymentFromVoucher(accountVouchersPay, true) + allocationForNeitherOpNorIPCrTribal);
            lineO.setPaidAmountNonTribal(nonTribalMissingIPDDeptPayment + getOtherPaymentFromVoucher(accountVouchersPay, false) + allocationForNeitherOpNorIPCrNonTribal);
            lineO.setRefundAmountTribal(tribalMissingIPDDeptRefund + getOtherRefundFromVoucher(accountVouchersRefund, true) + allocationForNeitherOpNorIPDrTribal);
            lineO.setRefundAmountNonTribal(nonTribalMissingIPDDeptRefund + getOtherRefundFromVoucher(accountVouchersRefund, false) + allocationForNeitherOpNorIPDrNonTribal);
            deptPayedAmt.put("Others", lineO);


            lineO.setTotalDueNonTribal(getDueFromVoucher(accountVouchersRefund, false));
            lineO.setTotalDueTribal(getDueFromVoucher(accountVouchersRefund, true));

//            lineO.setTotalAdvanceForNonTribal(getAdvanceFromVoucher(accountVouchersRefund, false));
//            lineO.setTotalAdvanceForTribal(getAdvanceFromVoucher(accountVouchersRefund, true));


        }

        final Map<String, List<AccountVoucherLine>> charityDeptSOMap = getDeptAVLMap(commonsId, deptCatMap, "opd");

        for (Map.Entry<String, List<AccountVoucherLine>> dept : charityDeptSOMap.entrySet()) {
            ReportLine reportLine = deptPayedAmt.get(dept.getKey());
            if (reportLine==null){
                reportLine = new ReportLine();
                deptPayedAmt.put(dept.getKey(),reportLine);
            }
            reportLine.setTotalCharityNonTribal(getAmountFromAVL(dept.getValue(), false));
            reportLine.setTotalCharityTribal(getAmountFromAVL(dept.getValue(), true));
        }
        {
            ReportLine others = deptPayedAmt.get("Others");
            others.setTotalCharityNonTribal(getAmountFromAVL(opdDeptMissingSOs,false));
            others.setTotalCharityTribal(getAmountFromAVL(opdDeptMissingSOs,true));
        }
        {
            List<AccountVoucherLine> ipCharity = getAVLFromSO("ipd");
            ReportLine ip = deptPayedAmt.get("IP");
            ip.setTotalCharityTribal(getAmountFromAVL(ipCharity, true));
            ip.setTotalCharityNonTribal(getAmountFromAVL(ipCharity, false));
        }

      /*  List<DepartmentReport> departmentReports = new ArrayList<DepartmentReport>();
        for (Map.Entry<String, List<AccountVoucherLine>> dept : departmentOpdPaymentAVL.entrySet()) {
            DepartmentReport departmentReport = new DepartmentReport();

            double billAmountTribal = getTotalSum(dept.getValue(),true);
            double totalCharityTribal = getCharitySum(dept.getValue(), true);
            double paidAmountTribal = getAmountFromAVL(dept.getValue(), true);
            double refundAmountTribal = getRefundAmount(departmentInvoiceMap.get(dept.getKey()), true);

            double billAmountNonTribal = getTotalSum(dept.getValue(), false);
            double totalCharityNonTribal = getCharitySum(dept.getValue(), false);
            double paidAmountNonTribal = getPaidAmount(dept.getValue(), false);
            double refundAmountNonTribal = getRefundAmount(departmentInvoiceMap.get(dept.getKey()), false);

            departmentReport.setDepartment(dept.getKey().trim());
            departmentReport.setTribal(new DepartmentReport.ReportLine(totalCharityTribal, paidAmountTribal, billAmountTribal,refundAmountTribal));
            departmentReport.setNonTribal(new DepartmentReport.ReportLine(totalCharityNonTribal, paidAmountNonTribal, billAmountNonTribal,refundAmountNonTribal));
            departmentReport.setTotal(new DepartmentReport.ReportLine(totalCharityTribal + totalCharityNonTribal,
                    paidAmountTribal + paidAmountNonTribal, billAmountTribal + billAmountNonTribal,refundAmountTribal+refundAmountNonTribal));
            departmentReports.add(departmentReport);
        }*/

        return deptPayedAmt;
    }

    private Map<String, List<AccountVoucherLine>> getDeptAVLMap(Integer commonsId, Map<String, List<Integer>> deptCatMap, String caresetting) {
        List<AccountVoucherLine> charitySOs = getAVLFromSO(caresetting);
        String soids = getCSVForSOID(charitySOs);
        Map<Integer, Integer> catCharitySOMap = getCatAVLMap(commonsId, soids);
        addCategoryToAVL(charitySOs, catCharitySOMap);
        return getSaleOrdersForDepartments(charitySOs, deptCatMap, true);
    }

    private List<AccountVoucherLine> getAVLFromSO(String caresetting) {
        String charitySO = "SELECT so.id,discount_amount,rpa.\"x_Is_Tribal\" FROM sale_order so" +
                "  LEFT JOIN res_partner_attributes rpa on rpa.partner_id=so.partner_id" +
                " where state!='draft' and state!='cancel' and care_setting='" +caresetting+
                "' and discount_amount>0 and cast(date_confirm as DATE) between '2016-04-01' and '2016-04-13'";

        return getCharitySOs(charitySO);
    }

    private List<AccountVoucherLine> getCharitySOs(String charitySO) {
        final List<AccountVoucherLine> charity = new LinkedList<AccountVoucherLine>();
        getErpJdbcTemplate().query(charitySO, new RowMapper<AccountVoucherLine>() {
            public AccountVoucherLine mapRow(ResultSet resultSet, int i) throws SQLException {
                AccountVoucherLine line = new AccountVoucherLine();
                line.setSOId((resultSet.getInt(1)));
                line.setAllocation(resultSet.getDouble(2));
                line.setTribal(Boolean.valueOf(resultSet.getString(3)));
                charity.add(line);
                return null;
            }
        });
        return charity;
    }

    private double getMissingIPDDeptPayment(String cr, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(opdDeptMissingAVLs)){
            for (AccountVoucherLine opdDeptMissingAVL : opdDeptMissingAVLs) {
                if (opdDeptMissingAVL.getTribal()==tribal &&
                        opdDeptMissingAVL.getType()==Utils.getTransactionType(cr)) {
                    total+=opdDeptMissingAVL.getAllocation();
                }
            }
        }
        return total;
    }

    private double getAllocationForNeitherOpNorIP(List<AccountVoucher> accountVouchersPay, boolean cr, boolean tribal) {
        double total = 0;
        if(Utils.isEmptyList(accountVouchersPay)){
            logger.error("Empty list or map " + accountVouchersPay);
            return total;
        }
        for (AccountVoucher voucher : accountVouchersPay) {
            if (voucher.getTribal()==tribal){
                List<AccountVoucherLine> accountVoucherLines = cr ? voucher.getCrLines() : voucher.getDrLines();
                if(Utils.isEmptyList(accountVoucherLines)){

                }else{
                    for (AccountVoucherLine accountVoucherLine : accountVoucherLines) {
                        String soCareSetting = accountVoucherLine.getSOCareSetting();
                        if("ipd".equalsIgnoreCase(soCareSetting)||"opd".equalsIgnoreCase(soCareSetting)){

                        }else{
                            total+=accountVoucherLine.getAllocation();
                        }
                    }
                }
            }

        }
        return total;
    }


    private void printAmountAllcation(List<AccountVoucher> accountVouchersPay, boolean cr) {
        if(Utils.isEmptyList(accountVouchersPay)){
            logger.error("Empty list or map " + accountVouchersPay);
            return;
        }
        double total = 0;
        for (AccountVoucher voucher : accountVouchersPay) {

            List<AccountVoucherLine> accountVoucherLines = cr ? voucher.getCrLines() : voucher.getDrLines();
            if(Utils.isEmptyList(accountVoucherLines)){

            }else{
                for (AccountVoucherLine accountVoucherLine : accountVoucherLines) {
                    logger.error("SO="+accountVoucherLine.getSOName() + " Allocation="+accountVoucherLine.getAllocation());
                    total+=accountVoucherLine.getAllocation();
                }
            }
        }
        logger.error("Total="+total);

    }

    private double getOtherRefundFromVoucher(List<AccountVoucher> accountVouchersPay, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(accountVouchersPay)) {
            for (AccountVoucher avl : accountVouchersPay) {
                if (avl.getTribal()==tribal){
                    total += (avl.getRefundWithoutReason());
                }
            }
        }
        return total;
    }


    private double getDueFromVoucher(List<AccountVoucher> accountVouchersPay, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(accountVouchersPay)) {
            for (AccountVoucher avl : accountVouchersPay) {
                if (avl.getTribal()==tribal){
                    total += (avl.getCustomerDue());
                }
            }
        }
        return total;
    }
    private double getAdvanceFromVoucher(List<AccountVoucher> accountVouchersPay, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(accountVouchersPay)) {
            for (AccountVoucher avl : accountVouchersPay) {
                if (avl.getTribal()==tribal){
                    total += (avl.getAdvanceWithoutReason());
                }
            }
        }
        return total;
    }

    private double getOtherPaymentFromVoucher(List<AccountVoucher> accountVouchersPay, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(accountVouchersPay)) {
            for (AccountVoucher avl : accountVouchersPay) {
                if (avl.getTribal()==tribal){
                    total += (avl.getPaymentWithoutReason());
                }
            }
        }
        return total;
    }

    private Map<String, List<Integer>> getDeptCatListMap() {
        String deptQuery = "select department_name,category_id from syncjob_department_category_mapping";
        final Map<String,List<Integer>> deptCatMap = new HashMap<String, List<Integer>>();
        getErpJdbcTemplate().query(deptQuery, new RowMapper<Void>() {
            public Void mapRow(ResultSet resultSet, int i) throws SQLException {
                String deptName = resultSet.getString(1);
                List<Integer> catList = deptCatMap.get(deptName);
                if (catList == null) {
                    catList = new ArrayList<Integer>();
                    deptCatMap.put(deptName, catList);
                }
                catList.add(resultSet.getInt(2));
                return null;
            }
        });
        return deptCatMap;
    }

    private Map<Integer, Integer> getCatAVLMap(Integer commonsId, String idcsv) {
        String opdCategQry = "SELECT sol.order_id,max(pt.categ_id) from sale_order_line sol " +
                "LEFT JOIN product_product pp on pp.id = sol.product_id " +
                "LEFT JOIN product_template pt on pt.id = pp.product_tmpl_id " +
                "where pt.categ_id not in (" +commonsId+
                ") and order_id in (" +idcsv+
                ") " +
                "GROUP BY sol.order_id";
        final Map<Integer,Integer> soCatMap = new HashMap<Integer,Integer>();
        getErpJdbcTemplate().query(opdCategQry, new RowMapper<Void>() {
            public Void mapRow(ResultSet resultSet, int i) throws SQLException {
                soCatMap.put(resultSet.getInt(1),resultSet.getInt(2));
                return null;
            }
        });
        return soCatMap;
    }

    private void addCategoryToAVL(List<AccountVoucherLine> opdAVL, Map<Integer, Integer> soCatMap) {
        if(Utils.isEmptyMap(soCatMap)|| Utils.isEmptyList(opdAVL)){
            logger.error("Empty list or map " + opdAVL + " : "+soCatMap);
            return;
        }
        for (AccountVoucherLine accountVoucherLine : opdAVL) {
            if (accountVoucherLine.getSOId() >0){
                Integer integer = soCatMap.get(accountVoucherLine.getSOId());
                if (integer!=null){
                    accountVoucherLine.setCategoryId(integer);
                }else {
                    logger.error("No Sale Category found for id " + accountVoucherLine.getSOName());
                }
            }
        }
    }

    private String getCSVForSOID(List<AccountVoucherLine> opdAVL) {
        if(Utils.isEmptyList(opdAVL)){
            logger.error("Empty list or map " + opdAVL);
            return "";
        }
        StringBuffer sb = new StringBuffer();
        boolean first = true;
        for (AccountVoucherLine accountVoucherLine : opdAVL) {
            if (accountVoucherLine.getSOId() >0){
                if (first){
                    first = false;
                    sb.append(accountVoucherLine.getSOId());
                }else{
                    sb.append(String.valueOf(",")+accountVoucherLine.getSOId());
                }
            }
        }
        return sb.toString();
    }

    private List<AccountVoucherLine> getSaleOrdersForPaymentForCareSetting(List<AccountVoucher> accountVouchersPay, String careSetting,boolean cr) {
        List<AccountVoucherLine> lines = new ArrayList<AccountVoucherLine>();
        if(Utils.isEmptyList(accountVouchersPay)){
            logger.error("Empty list or map " + accountVouchersPay);
            return lines;
        }

        for (AccountVoucher voucher : accountVouchersPay) {
            List<AccountVoucherLine> accountVoucherLines = cr ? voucher.getCrLines() : voucher.getDrLines();
            if (!Utils.isEmptyList(accountVoucherLines)){
                for (AccountVoucherLine accountVoucherLine : accountVoucherLines) {
                    if (careSetting.equalsIgnoreCase(accountVoucherLine.getSOCareSetting())){
                        lines.add(accountVoucherLine);
                    }
                }
            }
        }
        return lines;
    }

    public void allocatePayedAmount(List<AccountVoucher> accountVouchersPay) {

        if(Utils.isEmptyList(accountVouchersPay)){
            logger.error("Empty list or map " + accountVouchersPay);
            return;
        }


        for (AccountVoucher voucher : accountVouchersPay) {
            boolean processed = false;
            /*
-- amount -ve, balance -ve gave refund for nothing specific we owe money if no SO
-- amount -ve, balance -ve gave refund for nothing specific we refund some part of an SO
-- amount -ve, balance 0 gave refund for nothing specific we refund SO completely
-- amount -ve, balance +ve gave refund for nothing specific we refund SO completely and some extra to customer

*/
                if (voucher.getAmount()<0){
                    if(voucher.getBalanceAmount()<=0) {
                        if (Utils.isEmptyList(voucher.getDrLines())) {
                            voucher.setRefundWithoutReason(-1 * voucher.getAmount()+voucher.getRefundWithoutReason());
                        } else {
                            double amountInvoucher = -1 * voucher.getAmount();
                            for (AccountVoucherLine accountVoucherLine : voucher.getDrLines()) {
                                if (accountVoucherLine.getAmount() < amountInvoucher) {
                                    accountVoucherLine.setAllocation(accountVoucherLine.getAmount());
                                    amountInvoucher-=accountVoucherLine.getAmount();
                                } else {
                                    accountVoucherLine.setAllocation(amountInvoucher);
                                    amountInvoucher-=amountInvoucher;
                                }
                            }
                            if (amountInvoucher > 0) {
                                voucher.setRefundWithoutReason(amountInvoucher+voucher.getRefundWithoutReason());
                            }
                        }
                        processed = true;
                    }else if (voucher.getBalanceAmount()>0){
                        if (Utils.isEmptyList(voucher.getDrLines())) {
                            voucher.setRefundWithoutReason(-1 * voucher.getAmount()+voucher.getRefundWithoutReason());
                        }else {
                            double amountInvoucher = -1 * voucher.getAmount();
                            for (AccountVoucherLine accountVoucherLine : voucher.getDrLines()) {
                                if (accountVoucherLine.getAmount() < amountInvoucher) {
                                    accountVoucherLine.setAllocation(accountVoucherLine.getAmount());
                                    amountInvoucher-=accountVoucherLine.getAmount();
                                } else {
                                    accountVoucherLine.setAllocation(amountInvoucher);
                                    amountInvoucher-=amountInvoucher;
                                }
                            }
                            if (amountInvoucher > 0) {
                                voucher.setRefundWithoutReason(amountInvoucher+voucher.getRefundWithoutReason());
                            }
                        }
                        voucher.setCustomerDue(voucher.getBalanceAmount());
                        processed = true;
                    }
                }

/*
-- amount +ve balance -ve credit some amount without SO to customer :- Adv
-- amount +ve balance -ve credit some amount to SO :- Adv
-- amount +ve balance 0 credit some amount to SO
              */

                if (voucher.getAmount()>0){
                    if(voucher.getBalanceAmount()>=0) {
                        if (Utils.isEmptyList(voucher.getCrLines())) {
                            voucher.setPaymentWithoutReason(voucher.getAmount()+voucher.getPaymentWithoutReason());
//                            TODO: Split to due payment without SO if needed
                        } else {
                            double amountInvoucher = voucher.getAmount();
                            for (AccountVoucherLine accountVoucherLine : voucher.getCrLines()) {
                                if (accountVoucherLine.getAmount() < amountInvoucher) {
                                    accountVoucherLine.setAllocation(accountVoucherLine.getAmount());
                                    amountInvoucher-=accountVoucherLine.getAmount();
                                } else {
                                    accountVoucherLine.setAllocation(amountInvoucher);
                                    amountInvoucher-=amountInvoucher;
                                }
                            }
                            if (amountInvoucher > 0) {
                                voucher.setPaymentWithoutReason(amountInvoucher+voucher.getPaymentWithoutReason());
                            }
                        }
                        processed = true;
                    }else if (voucher.getBalanceAmount()<0){
                        if (Utils.isEmptyList(voucher.getCrLines())) {
                            voucher.setPaymentWithoutReason(voucher.getAmount()+voucher.getPaymentWithoutReason());
                        }else {
                            double amountInvoucher = voucher.getAmount();
                            for (AccountVoucherLine accountVoucherLine : voucher.getCrLines()) {
                                if (accountVoucherLine.getAmount() < amountInvoucher) {
                                    accountVoucherLine.setAllocation(accountVoucherLine.getAmount());
                                    amountInvoucher-=accountVoucherLine.getAmount();
                                } else {
                                    accountVoucherLine.setAllocation(amountInvoucher);
                                    amountInvoucher-=amountInvoucher;
                                }
                            }
                            if (amountInvoucher > 0) {
                                voucher.setPaymentWithoutReason(amountInvoucher+voucher.getPaymentWithoutReason());
                            }
                        }
                        voucher.setAdvanceWithoutReason(voucher.getBalanceAmount()+voucher.getAdvanceWithoutReason());
                        processed = true;
                    }
                }
            logger.error("Vocher "+voucher.getId()+ " processed="+processed);
            processed=false;
        }
    }

    private List<AccountVoucher> parseAccountVouchers(String accVouchersPay) {
        final List<AccountVoucher> accountVouchers = new ArrayList<AccountVoucher>();
        getErpJdbcTemplate().query(accVouchersPay, new RowMapper<AccountVoucher>() {
            public AccountVoucher mapRow(ResultSet resultSet, int i) throws SQLException {
                AccountVoucher voucher = new AccountVoucher();
                voucher.setId(resultSet.getInt(1));
                int contains = accountVouchers.indexOf(voucher);
                if (contains<1){
                    voucher.setAmount(resultSet.getDouble(2));
                    voucher.setBalanceAmount(resultSet.getDouble(3));
                    voucher.setBalanceBefore(resultSet.getDouble(4));
                    voucher.setNumber(resultSet.getString(5));
                    voucher.setTribal(Boolean.valueOf(resultSet.getString(14)));
                    accountVouchers.add(voucher);
//                    av.id,av.amount,av.balance_amount,av.balance_before_pay,av.number
                }else {
                    voucher= accountVouchers.get(contains);
                }
                int lineId = resultSet.getInt(6);
                if (lineId<0) {
                    return null;
                }
                AccountVoucherLine line = new AccountVoucherLine();
                line.setId(lineId);
                line.setAmount(resultSet.getDouble(7));
                line.setUnreconciled(resultSet.getDouble(8));
                line.setAmountOriginal(resultSet.getDouble(9));
                line.setType(Utils.getTransactionType(resultSet.getString(10)));

                line.setSOName((resultSet.getString(11)));
                line.setSOCareSetting((resultSet.getString(12)));
                line.setSOId((resultSet.getInt(13)));
                line.setTribal(Boolean.valueOf(resultSet.getString(14)));
                if (line.getType().equals(Utils.TRN_TYPE.CR)){
                    voucher.addCrLine(line);
                }else{
                    voucher.addDrLine(line);
                }

//                avl.id,avl.amount,avl.amount_unreconciled,avl.amount_original,avl.type
//                so.name,so.care_setting,so.id,rpa."x_Is_Tribal"
                return null;
            }
        });
        return accountVouchers;
    }

    private double getAmountFromAVL(List<AccountVoucherLine> invoices, boolean tribal) {
        double total = 0;
        if(!Utils.isEmptyList(invoices)) {
            for (AccountVoucherLine avl : invoices) {
                if (avl.getTribal()==tribal){
                    total += (avl.getAllocation());
                }
            }
        }
        return total;
    }

    private Map<String, List<AccountVoucherLine>> getSaleOrdersForDepartments(List<AccountVoucherLine> saleOrderCatMap, Map<String, List<Integer>> deptCatMap, boolean so) {
        Map<String,List<AccountVoucherLine>> departmentAVLMap = new HashMap<String, List<AccountVoucherLine>>();
        if(Utils.isEmptyMap(deptCatMap)|| Utils.isEmptyList(saleOrderCatMap)){
            logger.error("Empty list or map " + saleOrderCatMap + " : "+deptCatMap);
            return departmentAVLMap;
        }

        for (AccountVoucherLine avl : saleOrderCatMap) {
            String departmentForSO = getDepartmentForSO(avl.getCategoryId(), deptCatMap);
            if (Utils.isEmptyString(departmentForSO)){
                logger.error("No Department found for SO, " + avl.getSOName());
                if(so){
                    opdDeptMissingSOs.add(avl);
                }else{
                    opdDeptMissingAVLs.add(avl);
                }

                continue;
            }
            List<AccountVoucherLine> catList = departmentAVLMap.get(departmentForSO);
            if (catList==null){
                catList= new ArrayList<AccountVoucherLine>();
                departmentAVLMap.put(departmentForSO,catList);
            }
            catList.add(avl);
        }
        return departmentAVLMap;
    }

    private String getDepartmentForSO(Integer catId, Map<String, List<Integer>> deptCatMap) {
        if(Utils.isEmptyMap(deptCatMap)){
            logger.error("Empty list or map : "+deptCatMap);
            return null;
        }
        for (Map.Entry<String, List<Integer>> stringListEntry : deptCatMap.entrySet()) {
            List<Integer> value = stringListEntry.getValue();
            if (value.contains(catId)){
                return stringListEntry.getKey();
            }
        }
        return null;
    }

    @Override
    public List<ReportLine> getReportData() {
        List<ReportLine> ret = new ArrayList<ReportLine>();
        Map<String, ReportLine> saleOrdersForTodayWithDepartment = getSaleOrdersForTodayWithDepartment();
        for (Map.Entry<String, ReportLine> s : saleOrdersForTodayWithDepartment.entrySet()) {
            ReportLine value = s.getValue();
            double paidAmountNonTribal = value.getPaidAmountNonTribal();
            double paidAmountTribal = value.getPaidAmountTribal();
            double refundAmountNonTribal = value.getRefundAmountNonTribal();
            double refundAmountTribal = value.getRefundAmountTribal();
            value.setPaidAmountNonTribal(paidAmountNonTribal-refundAmountNonTribal);
            value.setPaidAmountTribal(paidAmountTribal-refundAmountTribal);
            value.setDepartment(s.getKey());
            ret.add(value);
        }
        return ret;
    }
}
