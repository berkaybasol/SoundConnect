create table tbl_user (
    id uuid primary key, user_name varchar(64), status varchar(32),
    email_verified boolean, erased_at timestamp, session_version bigint
);
create table tbl_permissions (id integer primary key, name varchar unique);
create table tbl_role (id integer primary key, name varchar unique);
create table user_roles (user_id uuid, role_id integer);
create table role_permissions (role_id integer, permission_id integer);
create table user_permissions (user_id uuid, permission_id integer);
create table "tbl_listener-profile" (user_id uuid);
create table tbl_city (id integer primary key, name varchar);
create table tbl_district (id integer primary key, name varchar);
create table tbl_neighborhood (id integer primary key, name varchar);
create table tbl_venue_applications (
    id uuid primary key, user_id uuid, venue_name varchar, venue_address varchar, status varchar,
    city_id integer, district_id integer, neighborhood_id integer, application_date timestamp
);
create table tbl_studio_applications (
    id uuid primary key, applicant_id uuid, studio_name varchar, studio_address varchar, status varchar,
    city_id integer, district_id integer, neighborhood_id integer, application_date timestamp
);

insert into tbl_city values (1,'İstanbul');
insert into tbl_district values (1,'Kadıköy');
insert into tbl_neighborhood values (1,'Moda');
insert into tbl_permissions values (1,'ADMIN_PANEL_ACCESS'),(2,'MANAGE_VENUE_APPLICATIONS'),(3,'MANAGE_STUDIO_APPLICATIONS');
insert into tbl_role values (1,'ROLE_ADMIN'),(2,'ROLE_LISTENER');
insert into role_permissions values (1,1);
insert into tbl_user values ('00000000-0000-0000-0000-000000000010','applicant','PENDING_VENUE_REQUEST',false,null,0);
insert into tbl_user values ('00000000-0000-0000-0000-000000000020','studio','PENDING_STUDIO_REQUEST',true,null,0);
insert into tbl_venue_applications values
    ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000010','Venue 1','Adres','PENDING',1,1,1,'2026-11-21 12:00:00'),
    ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000010','Venue 2','Adres','PENDING',1,1,1,'2026-11-21 12:00:00'),
    ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000010','Approved','Adres','APPROVED',1,1,1,'2026-11-21 13:00:00');
insert into tbl_studio_applications values
    ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000020','Studio 1','Stüdyo adresi','PENDING',1,1,1,'2026-11-21 12:00:00'),
    ('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000020','Rejected','Adres','REJECTED',1,1,1,'2026-11-21 13:00:00');

alter table tbl_venue_applications add decision_id uuid;
alter table tbl_studio_applications add decision_id uuid;
alter table tbl_venue_applications add phone varchar(15);
alter table tbl_venue_applications add reviewed_by_id uuid;
alter table tbl_venue_applications add rejection_reason varchar(500);
alter table tbl_venue_applications add decision_date timestamp;
alter table tbl_studio_applications add phone varchar(15);
alter table tbl_studio_applications add reviewed_by_id uuid;
alter table tbl_studio_applications add rejection_reason varchar(500);
alter table tbl_studio_applications add decision_date timestamp;
update tbl_venue_applications set phone='05551112233' where venue_name='Venue 1';
update tbl_studio_applications set phone='05552223344' where studio_name='Studio 1';
update tbl_studio_applications set decision_date=timestamp '2026-11-21 14:00:00', rejection_reason='Adres eksik'
where status='REJECTED';
