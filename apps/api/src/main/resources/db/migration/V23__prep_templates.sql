create table prep_template (
    id uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null,
    display_order integer not null unique
);

create table prep_template_section (
    id uuid primary key default gen_random_uuid(),
    template_id uuid not null references prep_template(id) on delete cascade,
    title text not null,
    display_order integer not null,
    unique (template_id, display_order)
);

create table prep_template_item (
    id uuid primary key default gen_random_uuid(),
    section_id uuid not null references prep_template_section(id) on delete cascade,
    name text not null,
    display_order integer not null,
    unique (section_id, display_order)
);

insert into prep_template (code, name, display_order)
values
    ('examination', 'إكزامينيشن (كشف وتشخيص)', 1),
    ('anesthesia', 'أنستيزيا (تخدير موضعي)', 2),
    ('endo', 'إندو (سحب عصب)', 3);

insert into prep_template_section (template_id, title, display_order)
select id, 'تجهيز عام', 1 from prep_template where code = 'examination'
union all select id, 'دياجنوزيس', 2 from prep_template where code = 'examination'
union all select id, 'تجهيز عام', 1 from prep_template where code = 'anesthesia'
union all select id, 'الأنستيزيا', 2 from prep_template where code = 'anesthesia'
union all select id, 'تجهيز عام', 1 from prep_template where code = 'endo'
union all select id, 'أكسيس وتنظيف الكانالز', 2 from prep_template where code = 'endo';

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('ميرور (مرآة فحص)', 1),
    ('إكسبلورر (مسبار)', 2),
    ('تويزر (ملقط)', 3)
) item(name, display_order)
where (t.code = 'examination' and s.display_order = 1)
   or (t.code = 'anesthesia' and s.display_order = 1)
   or (t.code = 'endo' and s.display_order = 1);

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('قفازات ومناديل', 1),
    ('كوب ومحلول مضمضة', 2),
    ('شفاط اللعاب', 3)
) item(name, display_order)
where (t.code = 'examination' and s.display_order = 2)
   or (t.code = 'anesthesia' and s.display_order = 2)
   or (t.code = 'endo' and s.display_order = 2);

revoke all on prep_template, prep_template_section, prep_template_item from app_rw;
grant select on prep_template, prep_template_section, prep_template_item to app_rw;
