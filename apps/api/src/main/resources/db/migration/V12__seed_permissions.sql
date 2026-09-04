-- Seed permissions for main application
insert into permission (code, name) values
    ('emp', 'إدارة الموظفين'),
    ('quick', 'الوصول السريع'),
    ('ceo', 'لوحة التحكم التنفيذية'),
    ('tasksTab', 'إدارة المهام'),
    ('acadVerify', 'التحقق من البرامج الأكاديمية'),
    ('acadEdit', 'تعديل البرامج الأكاديمية');

-- Seed permissions for inventory area
insert into permission (code, name) values
    ('tray', 'أدراج الأدوات'),
    ('issue', 'إصدار المواد'),
    ('procs', 'الإجراءات الطبية'),
    ('myprocs', 'إجراءاتي'),
    ('manage', 'إدارة المخزون'),
    ('orders', 'طلبات الشراء'),
    ('receive', 'استلام البضائع'),
    ('returns', 'إرجاع البضائع'),
    ('suppliers', 'إدارة الموردين'),
    ('dash', 'لوحة معلومات المخزون'),
    ('profit', 'تحليل الربح'),
    ('analytics', 'التحليلات المتقدمة'),
    ('waste', 'تتبع الفاقد'),
    ('doctors', 'ملفات الأطباء'),
    ('supAnalysis', 'تحليل الموردين'),
    ('received', 'المستلمات'),
    ('itemAnalysis', 'تحليل المواد'),
    ('approvals', 'الموافقات'),
    ('ledger', 'دفتر الأستاذ');

-- Grant permissions to assistant role: emp + {tray, issue, procs, myprocs, manage}
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r
join permission p on p.code = any(array['emp', 'tray', 'issue', 'procs', 'myprocs', 'manage'])
where r.code = 'assistant';

-- Grant permissions to receptionist role: emp + {orders, receive, returns, suppliers, ledger}
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r
join permission p on p.code = any(array['emp', 'orders', 'receive', 'returns', 'suppliers', 'ledger'])
where r.code = 'receptionist';

-- Grant permissions to manager role: {quick, ceo, tasksTab, acadVerify, acadEdit, emp} + all 19 inventory areas
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r
join permission p on p.code = any(array[
    'quick', 'ceo', 'tasksTab', 'acadVerify', 'acadEdit', 'emp',
    'tray', 'issue', 'procs', 'myprocs', 'manage', 'orders', 'receive', 'returns', 'suppliers',
    'dash', 'profit', 'analytics', 'waste', 'doctors', 'supAnalysis', 'received', 'itemAnalysis', 'approvals', 'ledger'
])
where r.code = 'manager';

-- Grant permissions to owner role: all 25 permissions
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r, permission p
where r.code = 'owner';
