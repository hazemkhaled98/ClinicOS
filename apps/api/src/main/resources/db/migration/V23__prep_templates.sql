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
union all select id, 'الأنستيزيا', 1 from prep_template where code = 'anesthesia'
union all select id, 'تجهيز عام', 1 from prep_template where code = 'endo'
union all select id, 'أنستيزيا', 2 from prep_template where code = 'endo'
union all select id, 'أكسيس وتنظيف الكانالز', 3 from prep_template where code = 'endo'
union all select id, 'الأوبتريشن (الحشو)', 4 from prep_template where code = 'endo'
union all select id, 'إنهاء وتعقيم', 5 from prep_template where code = 'endo';

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('ميرور (مرآة فحص)', 1),
    ('إكسبلورر (مسبار)', 2),
    ('تويزر (ملقط)', 3),
    ('جلوفز ومناديل', 4),
    ('كوب ومحلول مضمضة', 5)
) item(name, display_order)
where t.code = 'examination' and s.display_order = 1;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('خافض لسان/تشيك ريتراكتور', 1),
    ('جوز (شاش معقّم)', 2),
    ('إنترا-أورال كاميرا (لو متاح)', 3),
    ('كارت تسجيل الحالة', 4)
) item(name, display_order)
where t.code = 'examination' and s.display_order = 2;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('توبيكال جل (تخدير سطحي)', 1),
    ('أسبيريتنج سرنجة', 2),
    ('كاربيول أنستيتيك (كبسولة بنج)', 3),
    ('نيدل مناسبة (شورت/لونج)', 4),
    ('قطن وجوز', 5)
) item(name, display_order)
where t.code = 'anesthesia';

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('ميرور وإكسبلورر وتويزر', 1),
    ('سكشن (شفّاط)', 2),
    ('رَبر دام + فريم', 3)
) item(name, display_order)
where t.code = 'endo' and s.display_order = 1;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('توبيكال جل', 1),
    ('سرنجة وكاربيول ونيدل', 2)
) item(name, display_order)
where t.code = 'endo' and s.display_order = 2;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('تيربين هاند بيس + بَرز', 1),
    ('كي-فايلز (هاند فايلز) بمقاسات', 2),
    ('روتاري فايلز (لو متاح)', 3),
    ('هيبوكلورايت (NaOCl)', 4),
    ('إريجيشن سرنجة', 5),
    ('EDTA', 6),
    ('بيبر بوينتس', 7),
    ('إيبكس لوكيتور', 8)
) item(name, display_order)
where t.code = 'endo' and s.display_order = 3;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('جوتا بركا بوينتس', 1),
    ('سيلر (معجون حشو)', 2),
    ('سبريدر/بلجر', 3),
    ('تمبوراري فيلينج', 4)
) item(name, display_order)
where t.code = 'endo' and s.display_order = 4;

insert into prep_template_item (section_id, name, display_order)
select s.id, item.name, item.display_order
from prep_template_section s
join prep_template t on t.id = s.template_id
cross join lateral (values
    ('إكس-راي للتأكد', 1),
    ('تعقيم الأدوات', 2)
) item(name, display_order)
where t.code = 'endo' and s.display_order = 5;

revoke all on prep_template, prep_template_section, prep_template_item from app_rw;
grant select on prep_template, prep_template_section, prep_template_item to app_rw;
