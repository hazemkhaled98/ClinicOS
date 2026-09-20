#!/bin/sh
set -e

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<-SQL
    do \$\$
    begin
        if not exists (select 1 from pg_roles where rolname = 'app_rw') then
            create role app_rw noinherit login;
        end if;
    end \$\$;

    alter role app_rw with login password '$APP_DB_PASSWORD';
SQL
