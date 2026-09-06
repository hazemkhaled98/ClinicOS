package com.clinicos.ui;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import jakarta.annotation.security.PermitAll;

@Route(value = "select-clinic", autoLayout = false)
@PermitAll
public class ClinicPickerView extends VerticalLayout {

    public static final String SESSION_CLINIC_ID = "clinicId";
    public static final String SESSION_MEMBERSHIP_ID = "membershipId";
    public static final String SESSION_ROLE_CODE = "roleCode";
    public static final String SESSION_PERMISSIONS = "permissions";

    private final MembershipLookupService membershipLookupService;
    private final PermissionsService permissionsService;
    private final ActivityLogService activityLogService;

    public ClinicPickerView(MembershipLookupService membershipLookupService,
            PermissionsService permissionsService, ActivityLogService activityLogService) {
        this.membershipLookupService = membershipLookupService;
        this.permissionsService = permissionsService;
        this.activityLogService = activityLogService;

        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);
        getElement().setAttribute("dir", "rtl");

        AuthenticatedUser user = currentUser();
        if (user == null) {
            UI.getCurrent().navigate("login");
            return;
        }

        List<Membership> memberships;
        TenantContext.enterAuthMode();
        try {
            memberships = membershipLookupService.findByUserId(user.getId());
        } finally {
            TenantContext.exitAuthMode();
        }

        if (memberships.isEmpty()) {
            Icon icon = new Icon(VaadinIcon.EXCLAMATION_CIRCLE);
            Paragraph message = new Paragraph("لا توجد عيادات مسجلة. لا تملك صلاحية الدخول إلى أي عيادة.");

            Div emptyState = new Div(icon, message);
            emptyState.addClassName("clinicos-empty-state");
            icon.addClassName("clinicos-empty-state-icon");
            message.addClassName("clinicos-empty-state-message");

            add(emptyState);
            return;
        }

        selectAndRedirect(memberships.getFirst());
    }

    private void selectAndRedirect(Membership membership) {
        TenantContext.set(membership.clinicId());
        MembershipAccess access;
        try {
            activityLogService.log(
                    membership.clinicId(),
                    membership.membershipId(),
                    "login",
                    "session");

            access = permissionsService.accessFor(membership.membershipId());
        } finally {
            TenantContext.clear();
        }

        VaadinSession session = VaadinSession.getCurrent();
        session.setAttribute(SESSION_CLINIC_ID, membership.clinicId());
        session.setAttribute(SESSION_MEMBERSHIP_ID, membership.membershipId());
        session.setAttribute(SESSION_ROLE_CODE, access.roleCode());
        session.setAttribute(SESSION_PERMISSIONS, List.copyOf(access.permissionCodes()));

        UI.getCurrent().navigate("");
    }

    private static AuthenticatedUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}
