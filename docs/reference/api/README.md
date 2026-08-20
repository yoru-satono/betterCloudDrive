# API 业务域参考

本目录按稳定业务域保存 HTTP 接口的精确定义。先阅读 [API 总入口](../API.md) 了解基础地址、统一响应、错误码、认证、分页和通用对象约定。

## 业务域

- [认证与账号](AUTH.md)
- [文件管理](FILES.md)
- [上传、下载与预览](TRANSFERS.md)
- [收藏、标签与版本](ORGANIZATION.md)
- [分享](SHARING.md)
- [管理员](ADMIN.md)
- [团队协作](TEAMS.md)
- [通知](NOTIFICATIONS.md)
- [OnlyOffice](OFFICE.md)

## 维护边界

- 新增或修改 Controller、DTO、Security matcher 时，更新对应业务域。
- 跨多个业务域共享的认证、响应、错误和分页约定只更新总入口。
- 同一路径只在一个业务域正文中定义；其他位置通过链接引用。
- 设计原因放在架构或 ADR，部署和值班步骤放在运维文档。
