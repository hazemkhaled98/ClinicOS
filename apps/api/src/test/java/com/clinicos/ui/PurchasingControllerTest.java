package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.PurchasingService;
import com.clinicos.inventory.PurchasingService.Order;
import com.clinicos.inventory.PurchasingService.OrderLineRequest;
import com.clinicos.inventory.PurchasingService.ReceiveLine;
import com.clinicos.inventory.PurchasingService.ReturnLineRequest;
import com.clinicos.inventory.PurchasingService.Supplier;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;
import com.clinicos.shared.jooq.enums.PoStatus;

import jakarta.servlet.http.HttpSession;

class PurchasingControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ATTACHMENT = UUID.randomUUID();

    private LayoutModel layoutModel;
    private PurchasingService purchasingService;
    private ActivityLogService activityLogService;
    private AttachmentService attachmentService;
    private PurchasingController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        purchasingService = mock(PurchasingService.class);
        activityLogService = mock(ActivityLogService.class);
        attachmentService = mock(AttachmentService.class);
        controller = new PurchasingController(layoutModel, purchasingService, activityLogService, attachmentService);
        model = new ExtendedModelMap();
    }

    @Test
    void anonymousSessionRedirectsEveryScreenToInventory() {
        HttpSession session = mock(HttpSession.class);

        assertThat(controller.orders(null, session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.receive(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.received(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.returns(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.suppliers(false, session, model)).isEqualTo("redirect:/inventory");
        verify(purchasingService, never()).shortages(any());
        verify(purchasingService, never()).suppliers(any(), anyBoolean());
    }

    @Test
    void ordersScreenRendersShortagesSuppliersAndOrders() {
        HttpSession session = session("assistant", "orders");

        String view = controller.orders(null, session, model);

        assertThat(view).isEqualTo("inventory-orders");
        verify(purchasingService).shortages(CLINIC);
        verify(purchasingService).suppliers(CLINIC, false);
        verify(purchasingService).orders(CLINIC, Set.of(PoStatus.placed));
    }

    @Test
    void whatsappHandoffBuildsWaLinkWhenSupplierHasNumber() {
        HttpSession session = session("assistant", "orders");
        when(purchasingService.orders(CLINIC, Set.of(PoStatus.placed)))
                .thenReturn(placedOrders());
        when(purchasingService.suppliers(CLINIC, true))
                .thenReturn(List.of(SupplierShare.supplier("201000000000")));
        when(purchasingService.whatsappMessage(CLINIC, ORDER)).thenReturn("طلب مخزون جديد");

        controller.orders(ORDER, session, model);

        assertThat((String) model.getAttribute("waLink")).startsWith("https://wa.me/201000000000?text=");
    }

    @Test
    void placeOrderSkipsZeroQtyAndLogs() {
        HttpSession session = session("assistant", "orders");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(purchasingService.placeOrder(eq(CLINIC), any(), eq(SUPPLIER), any()))
                .thenReturn(placedOrders().get(0));

        String view = controller.placeOrder(SUPPLIER, List.of(ITEM),
                List.of(new BigDecimal("10")), Collections.singletonList(null), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/orders?wa=" + ORDER);
        verify(purchasingService).placeOrder(CLINIC, new Actor(MEMBERSHIP, "assistant"), SUPPLIER,
                List.of(new OrderLineRequest(ITEM, new BigDecimal("10"), null)));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.order.place", "purchase_order");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم وضع الطلب ✔");
    }

    @Test
    void placeOrderFiltersZeroQtyLines() {
        HttpSession session = session("assistant", "orders");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(purchasingService.placeOrder(eq(CLINIC), any(), eq(SUPPLIER), any()))
                .thenReturn(placedOrders().get(0));

        controller.placeOrder(SUPPLIER, List.of(ITEM), List.of(BigDecimal.ZERO), Collections.singletonList(null), session, redirect);

        verify(purchasingService).placeOrder(eq(CLINIC), any(), eq(SUPPLIER), ArgumentMatchers
                .<List<OrderLineRequest>>argThat(lines -> lines.isEmpty()));
    }

    @Test
    void placeOrderServiceRejectionFlashesErrorAndDoesNotLog() {
        HttpSession session = session("assistant", "orders");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(purchasingService.placeOrder(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("اختر صنفاً واحداً على الأقل للطلب"));

        String view = controller.placeOrder(SUPPLIER, List.of(ITEM), List.of(new BigDecimal("10")),
                Collections.singletonList(null), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/orders");
        verify(redirect).addFlashAttribute("toastType", "error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void receivePostsPhotoUploadAndReceipt() {
        HttpSession session = session("assistant", "receive");
        MultipartFile photo = mock(MultipartFile.class);
        when(photo.isEmpty()).thenReturn(false);
        when(attachmentService.upload(CLINIC, MEMBERSHIP, photo))
                .thenReturn(new AttachmentService.Attachment(ATTACHMENT, "key", "image/png", 1L));
        when(purchasingService.receive(eq(CLINIC), any(), eq(ORDER), any(), eq(ATTACHMENT)))
                .thenReturn(placedOrders().get(0));
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.receiveExec(ORDER, photo, List.of(UUID.randomUUID()),
                List.of(new BigDecimal("7")), List.of("LOT-1"), Collections.singletonList(null), session, model,
                redirect);

        assertThat(view).isEqualTo("redirect:/inventory/received");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.receive", "purchase_order");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم تسجيل الاستلام ✔");
    }

    @Test
    void receiveErrorCompensatesUploadedPhotoAndRerenders() {
        HttpSession session = session("assistant", "receive");
        MultipartFile photo = mock(MultipartFile.class);
        when(photo.isEmpty()).thenReturn(false);
        when(attachmentService.upload(CLINIC, MEMBERSHIP, photo))
                .thenReturn(new AttachmentService.Attachment(ATTACHMENT, "key", "image/png", 1L));
        when(purchasingService.receive(eq(CLINIC), any(), eq(ORDER), any(), any()))
                .thenThrow(new IllegalArgumentException("الكمية المستلمة أكبر من المطلوبة"));
        when(purchasingService.orders(CLINIC, Set.of(PoStatus.placed))).thenReturn(List.of());
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.receiveExec(ORDER, photo, List.of(UUID.randomUUID()),
                List.of(new BigDecimal("99")), Collections.singletonList(null), Collections.singletonList(null), session, model,
                redirect);

        assertThat(view).isEqualTo("inventory-receive");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("الكمية المستلمة أكبر من المطلوبة");
        verify(attachmentService).delete(CLINIC, ATTACHMENT);
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void saveSupplierPostsAndLogs() {
        HttpSession session = session("manager", "suppliers");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        PurchasingController.SupplierForm form = new PurchasingController.SupplierForm();
        form.setName("الريادة");
        form.setWhatsapp("201000000000");

        String view = controller.saveSupplier(form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/suppliers");
        verify(purchasingService).saveSupplier(CLINIC, new Actor(MEMBERSHIP, "manager"), null,
                new PurchasingService.SupplierRequest("الريادة", null, "201000000000", null, null, false));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.supplier.save", "supplier");
    }

    @Test
    void requestReturnFiltersNonPositiveQtyLinesAndLogs() {
        HttpSession session = session("assistant", "returns");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        UUID lineA = UUID.randomUUID();
        UUID lineB = UUID.randomUUID();
        UUID lineC = UUID.randomUUID();
        when(purchasingService.requestReturn(eq(CLINIC), any(), eq(ORDER), any())).thenReturn(null);

        String view = controller.requestReturn(ORDER, List.of(lineA, lineB, lineC),
                java.util.Arrays.asList(new BigDecimal("3"), BigDecimal.ZERO, null), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/returns");
        verify(purchasingService).requestReturn(eq(CLINIC), any(), eq(ORDER), ArgumentMatchers
                .<List<ReturnLineRequest>>argThat(lines -> lines.size() == 1
                        && lines.get(0).orderLineId().equals(lineA)
                        && lines.get(0).qty().compareTo(new BigDecimal("3")) == 0));
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.return.request", "supplier_return");
    }

    @Test
    void requestReturnServiceRejectionFlashesErrorAndDoesNotLog() {
        HttpSession session = session("assistant", "returns");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(purchasingService.requestReturn(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("الكمية المرتجعة غير صالحة"));

        String view = controller.requestReturn(ORDER, List.of(UUID.randomUUID()),
                List.of(new BigDecimal("3")), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/returns");
        verify(redirect).addFlashAttribute("toastType", "error");
        verify(redirect).addFlashAttribute("toastMessage", "الكمية المرتجعة غير صالحة");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void requestReturnWithoutPermissionRedirectsToInventory() {
        HttpSession session = session("assistant");
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.requestReturn(ORDER, List.of(UUID.randomUUID()),
                List.of(new BigDecimal("3")), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory");
        verify(purchasingService, never()).requestReturn(any(), any(), any(), any());
    }

    private static List<Order> placedOrders() {
        Order order = new Order(ORDER, SUPPLIER, "الريادة", "placed", OffsetDateTime.now(), null, null,
                new BigDecimal("100"), List.of(new ReceiveLine(UUID.randomUUID(), ITEM, "كمبوزيت", "عبوة",
                        new BigDecimal("10"), null, new BigDecimal("10"), null, null)));
        return List.of(order);
    }

    private static HttpSession session(String roleCode, String... permissionCodes) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn(roleCode);
        when(session.getAttribute(SessionKeys.PERMISSIONS)).thenReturn(List.of(permissionCodes));
        return session;
    }

    private static final class SupplierShare {
        static Supplier supplier(String whatsapp) {
            return new Supplier(SUPPLIER, "الريادة", "0100", whatsapp, 2, new BigDecimal("4.5"), null, false);
        }
    }
}