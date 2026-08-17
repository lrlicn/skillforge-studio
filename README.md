# SkillForge Studio

面向 AI skill 创作者的可视化管理工作台，支持本地导入、GitHub 授权工作区、Markdown 编辑预览、发布检查和版本交付。

## 目录

- `frontend/` React + TypeScript + Ant Design 前端
- `backend/` Java 21 + Spring Boot + MyBatis-Plus 后端
- `infra/` MySQL Docker Compose
- `docs/` 项目实施文档
- `scripts/` 本地启动辅助脚本

## 当前启动方式

```powershell
cd D:\code\skillforge-studio\frontend
npm install
npm run dev
```

后端需要 Maven Wrapper 或 Maven 环境后启动。Knife4j 地址为 `http://127.0.0.1:8080/doc.html`。

`D:\code\gzh\skills` 不是平台代码目录。平台不会自动扫描该目录；本地模式必须由用户主动导入，GitHub 模式必须完成 OAuth 授权。

本地敏感配置放在 `backend/src/main/resources/application-secrets.yml`，该文件已加入 `.gitignore`，不会提交到代码仓库。
