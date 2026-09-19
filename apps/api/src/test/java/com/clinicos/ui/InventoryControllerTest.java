package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.inventory.InventoryService.IssueResult;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.LocationKind;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

class InventoryControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();

    private LayoutModel layoutModel;
    private InventoryService inventoryService;
    private ActivityLogService activityLogService;
    private InventoryController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        inventoryService = mock(InventoryService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new InventoryController(layoutModel, inventoryService, activityLogService);
        model = new ExtendedModelMap();
        when(inventoryService.stock(any(), any())).thenReturn(List.of());
    }

    @Test
    void anonymousSessionRedirectsIndexToLoginAndSubScreensToInventory() {
        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(controller.index(request, response, session, model)).isEqualTo("redirect:/login");
        assertThat(controller.items(false, session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.tray(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.issue(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.ledger(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.approvals(session, model)).isEqualTo("redirect:/inventory");
        verify(inventoryService, never()).stock(any(), any());
        verify(inventoryService, never()).items(any(), any(Boolean.class));
    }

    @Test
    void noInventoryCodesRendersIndexEmptyButRedirectsEachSubScreen() {
        HttpSession session = session("receptionist", "emp");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(controller.index(request, response, session, model)).isEqualTo("inventory");
        assertThat(model.getAttribute("areas")).isEqualTo(List.of());

        assertThat(controller.items(false, session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.tray(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.issue(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.ledger(session, model)).isEqualTo("redirect:/inventory");
        assertThat(controller.approvals(session, model)).isEqualTo("redirect:/inventory");
        verify(inventoryService, never()).stock(any(), any());
        verify(inventoryService, never()).items(any(), any(Boolean.class));
    }

    @Test
    void trayCodeAllowsTrayButRedirectsItems() {
        HttpSession session = session("assistant", "tray");

        assertThat(controller.tray(session, model)).isEqualTo("inventory-tray");
        verify(inventoryService).stock(CLINIC, LocationKind.tray);
        assertThat(controller.items(false, session, model)).isEqualTo("redirect:/inventory");
    }

    @Test
    void manageCodeRendersItemsWithLiveStock() {
        HttpSession session = session("assistant", "manage");

        String view = controller.items(false, session, model);

        assertThat(view).isEqualTo("inventory-items");
        verify(inventoryService).items(CLINIC, false);
    }

    @Test
    void createItemPostsToServiceAndLogs() {
        HttpSession session = session("assistant", "manage");
        InventoryController.ItemForm form = new InventoryController.ItemForm();
        form.setName("كمبوزيت");
        form.setUom("عبوة");
        form.setUnitCost(new BigDecimal("45.50"));
        var request = new ItemRequest("كمبوزيت", "عبوة", null, new BigDecimal("45.50"), null, null);

        String view = controller.createItem(form, session, model);

        assertThat(view).isEqualTo("redirect:/inventory/items");
        verify(inventoryService).createItem(CLINIC, new Actor(MEMBERSHIP, "assistant"), request);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.item.create", "inventory_item");
    }

    @Test
    void createItemValidationErrorRerendersWithToast() {
        HttpSession session = session("assistant", "manage");
        when(inventoryService.createItem(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("الاسم ووحدة القياس والتكلفة مطلوبة"));
        InventoryController.ItemForm form = new InventoryController.ItemForm();

        String view = controller.createItem(form, session, model);

        assertThat(view).isEqualTo("inventory-items");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("الاسم ووحدة القياس والتكلفة مطلوبة");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void requestChangePostsEditToServiceAndLogs() {
        HttpSession session = session("assistant", "manage");
        InventoryController.ItemForm form = new InventoryController.ItemForm();
        form.setName("كمبوزيت معدّل");
        form.setUom("عبوة");
        form.setUnitCost(new BigDecimal("50.00"));
        var proposed = new ItemRequest("كمبوزيت معدّل", "عبوة", null, new BigDecimal("50.00"), null, null);
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.requestChange(ITEM, "edit", form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/items");
        verify(inventoryService).requestItemChange(CLINIC, new Actor(MEMBERSHIP, "assistant"),
                ITEM, ChangeRequestKind.edit, proposed);
        verify(redirect).addFlashAttribute("toastMessage", "بانتظار موافقة المدير");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.item.request-change", "inventory_change_request");
    }

    @Test
    void issuePostMapsIllegalArgumentToUnprocessableEntity() {
        HttpSession session = session("assistant", "issue");
        when(inventoryService.issue(eq(CLINIC), any(), eq(ITEM), eq(LocationKind.store), any()))
                .thenThrow(new IllegalArgumentException("الكمية المطلوبة غير صالحة"));

        assertThatThrownBy(() -> controller.issueExec(ITEM, new BigDecimal("5"), session, model))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void issueLogsOnlyWhenIssuedQuantityIsPositive() {
        HttpSession session = session("assistant", "issue");
        when(inventoryService.issue(eq(CLINIC), any(), eq(ITEM), eq(LocationKind.store), any()))
                .thenReturn(new IssueResult(ITEM, LocationKind.store, new BigDecimal("15"),
                        new BigDecimal("10"), true));

        String view = controller.issueExec(ITEM, new BigDecimal("15"), session, model);

        assertThat(view).isEqualTo("inventory-issue :: stockTable");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.issue", "stock_movement");
    }

    @Test
    void issueDoesNotLogWhenNoStockIssued() {
        HttpSession session = session("assistant", "issue");
        when(inventoryService.issue(eq(CLINIC), any(), eq(ITEM), eq(LocationKind.store), any()))
                .thenReturn(new IssueResult(ITEM, LocationKind.store, new BigDecimal("5"),
                        BigDecimal.ZERO, false));

        String view = controller.issueExec(ITEM, new BigDecimal("5"), session, model);

        assertThat(view).isEqualTo("inventory-issue :: stockTable");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("لا يوجد رصيد متاح لهذا الصنف");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void decideApproveAppliesAndLogsApproval() {
        HttpSession session = session("owner", "approvals");

        String view = controller.decide(REQUEST, true, session, model);

        assertThat(view).isEqualTo("redirect:/inventory/approvals");
        verify(inventoryService).applyItemChange(CLINIC, new Actor(MEMBERSHIP, "owner"), REQUEST, true);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.approval.approve", "inventory_change_request");
    }

    @Test
    void decideRejectAppliesAndLogsRejection() {
        HttpSession session = session("manager", "approvals");

        String view = controller.decide(REQUEST, false, session, model);

        assertThat(view).isEqualTo("redirect:/inventory/approvals");
        verify(inventoryService).applyItemChange(CLINIC, new Actor(MEMBERSHIP, "manager"), REQUEST, false);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.approval.reject", "inventory_change_request");
    }

    @Test
    void decideUnauthorizedPostRedirectsInsteadOfProcessing() {
        HttpSession session = session("assistant", "issue");

        String view = controller.decide(REQUEST, true, session, model);

        assertThat(view).isEqualTo("redirect:/inventory");
        verify(inventoryService, never()).applyItemChange(any(), any(), any(), any(Boolean.class));
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    private static HttpSession session(String roleCode, String... permissionCodes) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn(roleCode);
        when(session.getAttribute(SessionKeys.PERMISSIONS)).thenReturn(List.of(permissionCodes));
        return session;
    }
}