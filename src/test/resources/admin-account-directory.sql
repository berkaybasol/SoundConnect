create table tbl_user (
    id uuid primary key, user_name varchar(30) unique not null, public_code varchar(23) unique not null,
    status varchar(32), email_verified boolean not null, erased_at timestamp, session_version bigint not null,
    created_at timestamp
);
create table tbl_push_device (installation_id uuid primary key, user_id uuid, revoked_at timestamptz, token_hash varchar,
    token_ciphertext text, generation bigint not null);
create table tbl_permissions (id integer primary key, name varchar unique);
create table tbl_role (id integer primary key, name varchar unique);
create table user_roles (user_id uuid, role_id integer, primary key(user_id, role_id));
create table role_permissions (role_id integer, permission_id integer);
create table user_permissions (user_id uuid, permission_id integer);
create table "tbl_listener-profile" (user_id uuid unique);
create table tbl_musician_profile (user_id uuid unique);
create table tbl_studio_profile (user_id uuid unique);
create table tbl_organizer_profile (user_id uuid unique);
create table tbl_producer_profile (user_id uuid unique);
create table tbl_venues (owner_id uuid);
create table tbl_venue_applications (id uuid primary key, user_id uuid, status varchar, application_date timestamp, created_at timestamp);
create table tbl_studio_applications (id uuid primary key, applicant_id uuid, status varchar, application_date timestamp, created_at timestamp);
insert into tbl_permissions values (1,'ADMIN_PANEL_ACCESS'),(2,'READ_USERS'),(3,'MANAGE_USERS');
insert into tbl_role values (1,'ROLE_ADMIN'),(2,'ROLE_LISTENER'),(3,'ROLE_MUSICIAN'),(4,'ROLE_VENUE'),(5,'ROLE_STUDIO'),(6,'ROLE_PRODUCER');
insert into role_permissions values (1,1);
