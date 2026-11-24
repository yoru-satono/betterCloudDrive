# 上传与下载链路

本文说明个人/团队文件内容如何在客户端、后端、PostgreSQL、Redis 和 SeaweedFS 之间流动。具体请求参数与路径以 [API 参考](../../reference/API.md)为准。

## 分片上传

```mermaid
sequenceDiagram
    participant C as Client
    participant A as Backend API
    participant P as PostgreSQL
    participant R as Redis
    participant S as SeaweedFS
    C->>A: init(parentId, fileName, fileSize, md5Hash)
    A->>P: check identity, directory, name, quota; find candidates
    A->>S: verify candidate bytes and MD5
    alt verified candidate
        A->>P: create file; after commit update quota
        A-->>C: INSTANT + fileId
    else no valid candidate
        A->>P: compute chunks and create upload_sessions
        A-->>C: MULTIPART + sessionId, chunkSize, totalChunks
        loop each missing chunk
            C->>A: upload chunk + chunk md5
            A->>S: store verified temporary part
            A->>R: mark chunk bitmap
        end
        C->>A: complete(sessionId)
        A->>R: verify all chunks
        A->>S: compose and verify final object
        A->>P: create files + complete session
        A-->>C: fileId
    end
```

个人和团队上传使用相同状态机，但团队上传还要校验成员身份、目标父目录写权限和团队配额。`parentId=NULL` 表示根目录；非空父节点必须是同一作用域内未删除的目录。

初始化返回 `INSTANT` 时文件已创建，不产生会话；返回 `MULTIPART` 时只创建会话，不累计容量，文件尚不可见。请求不再传分片数，分片参数由服务端决定。每个分片先校验会话、编号、预期长度与必填摘要，再写临时对象并更新 Redis 位图。完成操作确认全部分片后，在流式组合最终对象时计算实际大小和 MD5，与初始化声明一致后才创建文件元数据并记录 `completed_file_id`。临时分片和位图在数据库提交后清理；摘要或大小不符时保留续传数据并清理本次最终对象。

## 幂等与失败处理

- 同一个已完成会话再次 complete，返回原 `completed_file_id`，不重复创建文件或累计配额。
- 分片重复提交会根据位图和会话状态处理，不应重复累计 received count。
- 对象组合成功但数据库提交失败属于跨存储不一致风险，后续清理必须能识别未被元数据引用的对象。
- 取消或过期会话删除临时分片；过期上传任务负责兜底清理。
- 统一初始化按摘要和大小选择秒传候选，读取真实源对象核验后可以复用已有对象路径，但仍会创建独立文件元数据；删除一个逻辑文件时不能误删仍被其他记录引用的对象。

Web、Qt 与 KMP 移动端的新任务只请求一次 `/init`，严格校验响应模式和必要字段。已有合法会话先查 `/status` 并补传缺片，不重新初始化；失效后才调用 `/init` 并处理两种结果。完成响应丢失继续用原会话幂等恢复。旧个人/团队 `/instant` 已移除，`419010` 停用；这是需要后端与三端协调发布的协议升级。

## 流式上传

WebDAV 等入口可以使用服务端流式上传。后端按固定大小读取输入、计算摘要并写临时分片，完成后组合对象和创建个人文件记录。该入口仍需执行目标目录、配额和文件名约束，不能绕过普通业务服务。

## 下载

下载前，后端先解析当前用户/分享访问上下文，再验证文件作用域、删除状态和权限。元数据校验通过后，后端从 `storage_path` 读取对象并返回内容。

```text
请求身份或一次性 ticket
  -> 解析个人 / 团队 / 分享作用域
  -> 校验资源与 canRead
  -> 读取 PostgreSQL 元数据
  -> 从 SeaweedFS 读取 storage_path
  -> 处理 Range / Content-Disposition
```

团队文件和文件夹下载可先创建短期下载 ticket，避免把长期 Bearer token 放进浏览器导航 URL。分享下载则按分享码、密码、有效期和访问次数单独授权。

## 文件夹压缩

文件夹下载可能转为后台任务或使用 `folder_zip_download_cache`：系统根据内容签名复用仍有效的压缩对象，结果文件存入对象存储或任务结果目录，并由清理任务按最后下载时间/有效期回收。生成压缩包前必须对目录树内资源执行访问校验。

## 修改检查

调整上传下载链路时同步检查：API、Web/Qt/KMP 移动端、会话幂等、个人/团队作用域、配额、Range 下载、临时对象清理、备份恢复和传输相关指标。
