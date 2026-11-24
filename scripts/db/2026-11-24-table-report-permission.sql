BEGIN;
INSERT INTO tbl_permissions(id, name, created_at, updated_at)
VALUES ('6bcbe338-c3f4-4713-82e1-ed45c5add290', 'MANAGE_TABLE_GROUP_REPORTS', now(), now())
ON CONFLICT(name) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM tbl_role r CROSS JOIN tbl_permissions p
WHERE r.name IN ('ROLE_ADMIN','ROLE_OWNER') AND p.name='MANAGE_TABLE_GROUP_REPORTS'
  AND NOT EXISTS (SELECT 1 FROM role_permissions existing
                  WHERE existing.role_id=r.id AND existing.permission_id=p.id);
COMMIT;
