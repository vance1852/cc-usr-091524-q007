# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

管理员账号保持不变：

- 用户名：`admin`
- 密码：`admin123`

另含五类角色的演示账号（密码均为 `123456`）：

| 用户名 | 角色 | 班组 | 可管理区域 |
| --- | --- | --- | --- |
| `zhaogcs` | 计划员 PLANNER | 计划调度组 | 注塑车间A区、动力站、包装车间、电机房、注塑车间B区 |
| `sunjh` | 计划员 PLANNER | 计划调度组 | 注塑车间A区、注塑车间B区 |
| `wangxj` | 巡检员 INSPECTOR | 甲班巡检组 | 注塑车间A区 |
| `lixj` | 巡检员 INSPECTOR | 乙班动力组 | 动力站 |
| `zhangwb` | 维修员 MAINTAINER | 维修一班 | 动力站、包装车间 |
| `qiansj` | 审计员 AUDITOR | 审计组 | 跨区域只读 |

## 角色与数据隔离

五类角色：管理员 `ADMIN`、计划员 `PLANNER`、巡检员 `INSPECTOR`、维修员 `MAINTAINER`、审计员 `AUDITOR`。

- 用户具有“所属班组（team_name）”和“可管理区域（managed_areas，逗号分隔）”；设备/巡检点/计划带区域，工单在建单时快照设备区域。
- JWT 仅携带角色与权限版本**快照**；服务端每请求都查库确认账号仍启用且 `permission_version` 未变。账号被禁用、角色/班组/区域/密码等授权发生变更时版本号自增，旧令牌**立即 401**。
- 粗粒度角色门槛由 `@RequireRole` + 拦截器统一处理；对象级与列表数据范围由 `AuthorizationService` / `ScopeService` 单点实现，控制器不写散落判断。
- 对象存在但无权访问统一返回 **403**，不存在才返回 **404**；列表、单对象、统计共用同一可见性谓词，结论一致，可防跨班组/跨区域 ID 猜测。
- 范围规则：管理员全量；审计员跨区域只读；计划员在本区域维护计划/模板/巡检点并可派工；巡检员只执行“分配给自己或本班组”的任务；维修员处理“获派或本区域”工单。
- 人员与授权维护：`/api/admin/users`（仅管理员）。

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
