import axios from 'axios'

/** 当前登录用户的前端只读视图，不包含密码哈希等服务端字段。 */
export interface CurrentUser {
  id: number
  username: string
  email: string
}

export interface LoginPayload {
  account: string
  password: string
}

export interface RegisterPayload {
  username: string
  email: string
  password: string
}

/** 工作区列表摘要，用于恢复用户最近导入的本地工作区。 */
export interface WorkspaceSummary {
  id: string
  name: string
  sourceType: 'LOCAL' | 'GITHUB'
  status: string
  fileCount: number
  totalSize: number
  updatedAt: string
}

/** 后端返回的目录树节点；只有文件节点拥有 fileId。 */
export interface WorkspaceTreeNode {
  key: string
  fileId: number | null
  name: string
  path: string
  nodeType: 'DIRECTORY' | 'FILE'
  mimeType: string | null
  sizeBytes: number | null
  sha256: string | null
  children: WorkspaceTreeNode[]
}

export interface WorkspaceImportResult {
  workspaceId: string
  workspaceName: string
  sourceType: 'LOCAL' | 'GITHUB'
  fileCount: number
  totalSize: number
}

/** 文本保存后返回的新版本，用于更新当前文件和工作区摘要。 */
export interface FileContentUpdateResult {
  fileId: number
  sizeBytes: number
  sha256: string
  workspaceTotalSize: number
}

/** GitHub 连接状态不包含 Access Token，前端只展示账号资料和已授权权限。 */
export interface GitHubConnectionView {
  authorizationAvailable: boolean
  connected: boolean
  githubUserId: number | null
  login: string | null
  displayName: string | null
  avatarUrl: string | null
  scopes: string[]
  connectedAt: string | null
  updatedAt: string | null
}

/** GitHub 仓库列表只保留选择和扫描所需字段。 */
export interface GitHubRepositoryView {
  id: number
  name: string
  fullName: string
  owner: string
  privateRepository: boolean
  defaultBranch: string
  description: string | null
  htmlUrl: string
  updatedAt: string
}

/** 仓库扫描结果以 SKILL.md 所在目录作为导入选择项。 */
export interface GitHubSkillView {
  name: string
  directoryPath: string
  manifestPath: string
}

/**
 * 所有接口统一携带浏览器会话 Cookie。开发环境通过 Vite 代理转发到 8080，避免前端保存认证 Token。
 */
const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN'
})

/** 首次写请求前获取 CSRF Cookie，避免跨站页面借用当前登录会话执行提交。 */
client.interceptors.request.use(async config => {
  const method = config.method?.toLowerCase()
  const mutating = method === 'post' || method === 'put' || method === 'patch' || method === 'delete'
  if (mutating && !document.cookie.split('; ').some(item => item.startsWith('XSRF-TOKEN='))) {
    await axios.get('/api/v1/system/csrf', { withCredentials: true })
  }
  return config
})

/** 业务功能模块复用同一个会话客户端，不各自创建认证和错误处理逻辑。 */
export const httpClient = client

/**
 * 会话过期是跨页面状态，由统一响应拦截器广播；界面根组件负责清理用户状态并返回登录页。
 */
export const AUTH_EXPIRED_EVENT = 'skillforge:auth-expired'
client.interceptors.response.use(
  response => response,
  error => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
    }
    return Promise.reject(error)
  }
)

export const authApi = {
  login: (payload: LoginPayload) => client.post<CurrentUser>('/auth/login', payload).then(response => response.data),
  register: (payload: RegisterPayload) => client.post<CurrentUser>('/auth/register', payload).then(response => response.data),
  logout: () => client.post('/auth/logout'),
  me: () => client.get<CurrentUser>('/auth/me').then(response => response.data)
}

export const workspaceApi = {
  list: () => client.get<WorkspaceSummary[]>('/workspaces').then(response => response.data),
  tree: (workspaceId: string) => client.get<WorkspaceTreeNode[]>(`/workspaces/${workspaceId}/tree`).then(response => response.data),
  rename: (workspaceId: string, name: string) => client.put<WorkspaceSummary>(`/workspaces/${workspaceId}`, { name }).then(response => response.data),
  remove: (workspaceId: string) => client.delete(`/workspaces/${workspaceId}`),

  /**
   * 文件本体与相对路径分开传输，服务端不会相信浏览器传入的任意服务器绝对路径。
   */
  importLocal: async (workspaceName: string, files: File[], relativePaths: string[]) => {
    const formData = new FormData()
    formData.append('workspaceName', workspaceName)
    files.forEach((file, index) => {
      formData.append('files', file, file.name)
      formData.append('relativePaths', relativePaths[index])
    })
    const response = await client.post<WorkspaceImportResult>('/workspaces/import-local', formData, { timeout: 120000 })
    return response.data
  },

  /** 文本响应可能被 Axios 解析为 JSON，因此统一转换为编辑器可显示的字符串。 */
  readTextFile: async (workspaceId: string, fileId: number) => {
    const response = await client.get(`/workspaces/${workspaceId}/files/${fileId}/content`, { responseType: 'text' })
    return typeof response.data === 'string' ? response.data : JSON.stringify(response.data, null, 2)
  },

  /** expectedSha256 让服务端在保存前检测文件是否已被其他页面修改。 */
  saveTextFile: (workspaceId: string, fileId: number, content: string, expectedSha256: string) =>
    client.put<FileContentUpdateResult>(`/workspaces/${workspaceId}/files/${fileId}/content`, { content, expectedSha256 })
      .then(response => response.data),

  fileContentUrl: (workspaceId: string, fileId: number) => `/api/v1/workspaces/${workspaceId}/files/${fileId}/content`
}

/** GitHub OAuth 仅用于连接当前平台账号，授权入口由后端校验配置和登录状态后返回。 */
export const githubConnectionApi = {
  status: () => client.get<GitHubConnectionView>('/connections/github').then(response => response.data),
  authorize: () => client.post<{ authorizationUrl: string }>('/connections/github/authorize').then(response => response.data),
  disconnect: () => client.delete('/connections/github')
}

/** 仓库 API 使用后端保存的加密 Token，前端请求中不包含任何 GitHub 凭据。 */
export const githubRepositoryApi = {
  list: () => client.get<GitHubRepositoryView[]>('/github/repositories').then(response => response.data),
  skills: (repository: string, ref: string) => client.get<GitHubSkillView[]>('/github/skills', { params: { repository, ref } }).then(response => response.data),
  importSkills: (payload: { repositoryFullName: string; branch: string; workspaceName: string; skillPaths: string[] }) =>
    client.post<WorkspaceImportResult>('/github/import', payload, { timeout: 120000 }).then(response => response.data)
}

/** 从统一错误响应中提取用户可理解的信息，网络异常时提供稳定兜底文案。 */
export function getApiErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return error.response?.data?.message || (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '服务暂时不可用')
  }
  return '操作失败，请稍后重试'
}
