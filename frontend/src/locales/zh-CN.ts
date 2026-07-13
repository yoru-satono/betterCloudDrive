export default {
  common: { success: '操作成功' },
  error: {
    internal: '服务暂时不可用，请稍后重试', validation: '提交的信息有误：{fields}', method_not_allowed: '不支持此操作',
    malformed_body: '提交的数据格式有误', missing_parameter: '缺少必要参数：{name}', invalid_parameter: '参数格式有误：{name}',
    bad_request: '请求有误', unauthorized: '请先登录', token_expired: '登录已过期，请重新登录', token_blacklisted: '登录状态已失效，请重新登录',
    forbidden: '没有权限执行此操作', not_found: '未找到所需内容', file_not_found: '文件不存在', conflict: '数据发生冲突',
    file_name_conflict: '当前目录中已有同名文件', rate_limited: '操作过于频繁，请稍后重试', storage_quota_exceeded: '存储空间不足', instant_upload_not_found: '未找到可秒传的文件',
    invalid_share_code: '分享码无效', share_password_required: '请输入分享密码', share_expired: '分享链接已过期', share_visit_limit: '分享访问次数已达上限',
    file_too_large: '文件超过大小限制', email_code_expired: '验证码已过期', email_code_mismatch: '验证码不正确', password_reset_failed: '密码重置失败',
    folder_download_limit_exceeded: '文件夹下载超过限制', storage_error: '存储服务暂时不可用', upload_failed: '上传失败', download_failed: '下载失败'
  },
  notification: {
    team_invite: { title: '团队邀请', content: '你收到了团队“{teamName}”的邀请' }, team_invite_accept: { title: '邀请已接受', content: '{userName} 已接受团队“{teamName}”的邀请' },
    team_invite_reject: { title: '邀请已拒绝', content: '{userName} 已拒绝团队“{teamName}”的邀请' }, team_quota_alert: { title: '团队配额告警', content: '团队“{teamName}”存储用量已达到 {threshold}%' },
    team_archive: { title: '团队已归档', content: '团队已被归档' }, team_unarchive: { title: '团队已取消归档', content: '团队已恢复使用' },
    team_restore: { title: '团队已恢复', content: '团队已从删除状态恢复' }, team_permission_update: { title: '权限已更新', content: '团队文件权限已更新' },
    team_delete: { title: '团队已删除', content: '团队已被删除' }, team_member_add: { title: '成员已加入', content: '团队新增了一位成员' },
    team_role_update: { title: '角色已更新', content: '团队成员角色已更新' }, team_member_remove: { title: '成员已移除', content: '团队成员已被移除' },
    team_owner_transfer: { title: '所有权已转让', content: '团队所有权已转让' }, team_office_save_failed: { title: '在线文档保存失败', content: '在线编辑内容保存失败，请重试' }
  },
  task: {
    failed: '任务执行失败', succeeded: '任务已完成', result_expired: '任务结果已过期清理', audit_export: { title: '导出团队审计日志' },
    folder_zip: { title: '生成文件夹 ZIP' }, onlyoffice_save: { title: '保存在线编辑文档' }, thumbnail: { title: '生成文件缩略图' }, team_cleanup: { title: '清空团队回收站' }
  },
  permission: { base_role: '基础角色权限：{role}', inherited_override: '继承自“{fileName}”的权限覆盖', current_override: '当前文件的权限覆盖' },
  activity: {
    invite_create: '创建了团队邀请', invite_accept: '接受了团队邀请', invite_reject: '拒绝了团队邀请', file_upload: '上传了文件',
    file_delete: '删除了文件', file_restore: '恢复了文件', share_create: '创建了分享', member_add: '添加了团队成员', role_update: '更新了成员角色'
  }
}
