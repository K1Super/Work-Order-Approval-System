# 接口规范

本文档为工单审批流转系统的 API 接口规范，内容摘录自《PROJECT_DOCUMENTATION.md》中「API 接口规范」章节。

## 1. API 接口规范

### 1.1 认证接口

#### POST /api/auth/login

**描述**：用户登录

**请求体**：

```json
{
  "username": "KLord",
  "password": "123456"
}
```

**响应体**：

```json
{
  "code": 20000,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400000,
    "user": {
      "id": 1,
      "username": "KLord",
      "realName": "KLord Administrator",
      "roles": ["SUPER_ADMIN"],
      "permissions": ["system:user", "workorder:view-all", "..."]
    }
  }
}
```

#### POST /api/auth/logout

**描述**：用户登出

**请求头**：`Authorization: Bearer <token>`

**响应体**：

```json
{
  "code": 20000,
  "message": "登出成功"
}
```

#### POST /api/auth/change-password

**描述**：修改密码

**请求体**：

```json
{
  "oldPassword": "原密码",
  "newPassword": "新密码"
}
```

### 1.2 工单接口

#### POST /api/workorder

**描述**：直接提交新工单

**权限**：`workorder:submit`

**请求体**：

```json
{
  "title": "请假申请",
  "content": "因私事请假一天",
  "orderType": "leave",
  "priority": 2,
  "remark": ""
}
```

#### POST /api/workorder/draft

**描述**：创建工单草稿

**权限**：`workorder:submit`

#### POST /api/workorder/{id}/submit

**描述**：提交草稿工单

**权限**：`workorder:submit`

#### GET /api/workorder/{id}

**描述**：查询工单详情

**权限**：`workorder:mine` 或 `workorder:view-all`

#### GET /api/workorder/my

**描述**：查询我的工单列表

**权限**：`workorder:mine`

**查询参数**：`status`、`orderType`、`keyword`、`pageNum`、`pageSize`

#### GET /api/workorder/list

**描述**：查询全部工单（管理员/管理层）

**权限**：`workorder:view-all` 或 `workorder:approve`

#### GET /api/workorder/pending

**描述**：查询待我审批的工单

**权限**：`workorder:pending`

#### POST /api/workorder/{id}/withdraw

**描述**：撤回工单

**权限**：`workorder:submit`

#### POST /api/workorder/{id}/terminate

**描述**：终止工单

**权限**：`system:user` 或 `workorder:approve`

#### POST /api/workorder/{id}/archive

**描述**：归档工单

**权限**：`system:user`

**数据权限**：校验当前用户对该工单的所有权

#### PUT /api/workorder/{id}

**描述**：更新工单信息

**权限**：`workorder:submit`

**数据权限**：仅发起人可改草稿

#### DELETE /api/workorder/{id}

**描述**：删除工单

**权限**：`workorder:submit`

### 1.3 审批接口

#### POST /api/approval/{taskId}/approve

**描述**：审批通过

**权限**：`workorder:approve`

**请求体**：

```json
{
  "comment": "同意",
  "workOrderId": 100
}
```

#### POST /api/approval/{taskId}/reject

**描述**：审批驳回

**权限**：`workorder:approve`

#### POST /api/approval/{taskId}/transfer

**描述**：转办任务

**权限**：`workorder:approve`

### 1.4 员工管理接口

#### GET /api/employee/list

**描述**：员工列表分页查询

**权限**：`system:user` 或 `workorder:view-all`

**查询参数**：`pageNum`、`pageSize`、`keyword`、`deptId`、`positionId`、`orgLevel`

#### GET /api/employee/{id}

**描述**：员工详情

**权限**：`system:user` 或 `workorder:view-all`

#### POST /api/employee

**描述**：创建员工

**权限**：`system:user`

#### PUT /api/employee/{id}

**描述**：更新员工

**权限**：`system:user`

#### DELETE /api/employee/{id}

**描述**：删除员工（逻辑删除）

**权限**：`system:user`

#### PUT /api/employee/{id}/status

**描述**：切换员工状态

**权限**：`system:user`

#### PUT /api/employee/{id}/reset-password

**描述**：重置员工密码

**权限**：`system:user`

**请求体**：

```json
{
  "operatorPassword": "当前操作密码（超管重置自己时需要）"
}
```

**响应体**：

```json
{
  "code": 20000,
  "message": "密码重置成功",
  "data": {
    "resetToken": "64 字符随机 token",
    "resetLink": "/password-reset?token=xxx"
  }
}
```

#### PUT /api/employee/{id}/roles

**描述**：分配角色

**权限**：`system:user`

#### GET /api/employee/export

**描述**：导出员工 CSV

**权限**：`system:user`

**响应**：`Content-Type: text/csv`

#### POST /api/employee/{id}/resign

**描述**：员工离职处理

**权限**：`system:user`

### 1.5 密码重置接口

#### POST /api/password-reset/validate

**描述**：验证重置 token 是否有效

**权限**：公开访问（permitAll）

#### POST /api/password-reset/confirm

**描述**：消费 token 并设置新密码

**权限**：公开访问（permitAll）

### 1.6 系统设置接口

#### GET /api/setting/{groupKey}

**描述**：按分组查询设置

#### PUT /api/setting

**描述**：更新设置

**权限**：`system:user`

### 1.7 文件接口

#### POST /api/file/upload

**描述**：文件上传

**权限**：已认证

**Content-Type**：`multipart/form-data`

#### GET /api/file/download/{filename}

**描述**：文件下载

---