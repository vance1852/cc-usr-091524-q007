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

管理员（始终可用）：

- 用户名：`admin`
- 密码：`admin123`

五类角色演示账号（密码均为 `123456`，可由管理员在 `/api/admin/users` 维护）：

| 用户名 | 角色 | 班组 | 可管理区域 |
| --- | --- | --- | --- |
| `zhaogcs` | PLANNER 计划员 | 计划调度组 | 注塑车间A区、注塑车间B区、包装车间 |
| `wangxj` | 巡检员 | 甲班巡检组 | —（按本人/本班组任务） |
| `lixj` | 巡检员 | 乙班动力组 | —（按本人/本班组任务） |
| `zhangwb` | 维修员 | 维修一班 | 动力站、电机房 |
| `chenaudit` | 审计员 | 审计组 | 跨区域（只读） |

## 角色与数据隔离

JWT 携带角色/班组/区域/权限版本快照，但**每次请求都以数据库为准**：账号必须存在且启用、
令牌 `pv` 必须等于用户 `permission_version`。角色/班组/区域/启停任一变更都会自增版本，
使旧令牌立即失效；账号禁用后旧令牌返回 401。

- **管理员 ADMIN**：全部读/写，含人员授权（`/api/admin/users`）。
- **计划员 PLANNER**：本区域设备/巡检点只读，巡检点/模板/计划可写、可生成本范围任务。
- **巡检员 INSPECTOR**：只能查看和执行**分配给自己或本班组**的任务，任务内设备/工单/异常/记录随任务可见。
- **维修员 MAINTAINER**：本区域设备/工单只读，处理**获派本人或本班组**及本区域的工单。
- **审计员 AUDITOR**：跨区域只读设备、工单、任务、异常与统计，任何写操作返回 403。

对象级越权（资源存在但无权访问）统一返回 **403**，资源不存在才返回 404；
列表过滤与对象校验共用 `AuthorizationService` 同一份范围计算，二者不会矛盾。
设备、工单、巡检任务、统计/仪表盘在同一账号下数据范围完全一致。

## 已实现的基础功能

- 登录签发 JWT（含角色/班组/区域/权限版本快照）、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 管理员维护人员与授权（`/api/admin/users`，支持建号、改角色/班组/区域、启停、撤销授权）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 按数据范围收敛的巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 按数据范围收敛的仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 测试

`mvn test` 使用内存数据库 H2 启动完整 Spring 上下文与真实过滤器链：

- `RbacAuthorizationTest`：五角色数据范围、对象级 403、跨班组 ID 猜测、
  禁用账号/权限变更/撤销导致旧令牌立即失效、审计员只读、统计口径一致性。
- `ProductionSeedSmokeTest`：用生产 `DataSeeder` 在 H2 上验证内置账号与班组隔离。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
