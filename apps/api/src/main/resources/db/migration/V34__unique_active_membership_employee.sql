create unique index uq_membership_active_employee
    on membership (clinic_id, employee_id)
    where employee_id is not null and status = 'active';
