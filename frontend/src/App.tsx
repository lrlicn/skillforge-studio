import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import {
  App as AntApp,
  Avatar,
  Button,
  Card,
  Checkbox,
  Divider,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Grid,
  Input,
  Layout,
  Menu,
  Modal,
  Segmented,
  Space,
  Spin,
  Tag,
  Tooltip,
  Tree,
  Typography,
  Upload,
} from 'antd'
import type { MenuProps } from 'antd/es'
import type { DataNode } from 'antd/es/tree'
import type { RcFile } from 'antd/es/upload/interface'
import {
  ArrowUpRight,
  CircleUserRound,
  ChevronDown,
  ChevronRight,
  FileImage,
  FileText,
  Files,
  Folder,
  Github,
  GitCompareArrows,
  Library,
  Link2,
  LayoutDashboard,
  LogOut,
  PanelLeft,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Trash2,
} from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'
import {
  AUTH_EXPIRED_EVENT,
  authApi,
  githubConnectionApi,
  githubRepositoryApi,
  getApiErrorMessage,
  workspaceApi,
  type CurrentUser,
  type GitHubConnectionView,
  type GitHubRepositoryView,
  type GitHubSkillView,
  type LoginPayload,
  type RegisterPayload,
  type WorkspaceSummary,
  type WorkspaceTreeNode
} from './api/client'
import GitHubChangesDrawer from './features/github-changes/GitHubChangesDrawer'
import { githubWorkspaceChangesApi } from './features/github-changes/api'
import type { GitHubWorkspaceChanges } from './features/github-changes/types'
import type { PreviewKind, ViewMode } from './features/editor/types'
import HomePage from './pages/HomePage'
import EditorPage from './pages/EditorPage'
import WorkspacesPage from './pages/WorkspacesPage'
import ConnectionsPage from './pages/ConnectionsPage'
import GitHubRepositoriesPage from './pages/GitHubRepositoriesPage'
import ProfilePage from './pages/ProfilePage'
import { formatBytes, formatDate } from './utils/format'

const { Header, Sider, Content, Footer } = Layout
const { Title, Text, Paragraph } = Typography
const { useBreakpoint } = Grid
const LocalEditor = lazy(() => import('./components/LocalEditor'))

type ImportMode = '目录' | '文件'
type AppPage = 'home' | 'editor' | 'workspaces' | 'connections' | 'github' | 'profile'

interface LocalImportFile {
  uid: string
  file: File
  relativePath: string
}

interface BrowserFileHandle {
  kind: 'file'
  name: string
  getFile: () => Promise<File>
}

interface BrowserDirectoryHandle {
  kind: 'directory'
  name: string
  values: () => AsyncIterableIterator<BrowserFileHandle | BrowserDirectoryHandle>
}

/** 递归读取用户明确选择的目录句柄，并保留包含根目录名的相对路径。 */
async function collectDirectoryFiles(
  directory: BrowserDirectoryHandle,
  parentPath: string,
  result: LocalImportFile[]
): Promise<void> {
  for await (const entry of directory.values()) {
    if (result.length >= 501) return
    const relativePath = `${parentPath}/${entry.name}`
    if (entry.kind === 'directory') {
      await collectDirectoryFiles(entry, relativePath, result)
    } else {
      const file = await entry.getFile()
      result.push({ uid: `${relativePath}-${file.lastModified}`, file, relativePath })
    }
  }
}

/** 主导航按职责拆分编辑、资源管理和外部连接，避免把 GitHub 授权混入本地工作区。 */
const menuItems: NonNullable<MenuProps['items']> = [
  { key: 'home', icon: <LayoutDashboard size={17} />, label: '工作台概览' },
  { key: 'editor', icon: <FileText size={17} />, label: '编辑工作台' },
  { key: 'workspaces', icon: <Folder size={17} />, label: '工作区管理' },
  { type: 'divider' },
  { key: 'connections', icon: <Link2 size={17} />, label: '连接管理' },
  { key: 'github', icon: <Github size={17} />, label: 'GitHub 仓库' }
]

const pageNames: Record<AppPage, string> = {
  home: '工作台概览',
  editor: '编辑工作台',
  workspaces: '工作区管理',
  connections: '连接管理',
  github: 'GitHub 仓库',
  profile: '账号资料'
}

/** 将后端目录树转换为 Ant Design Tree 所需结构，同时保留稳定的节点 key。 */
function toAntTree(nodes: WorkspaceTreeNode[]): DataNode[] {
  return nodes.map(node => ({
    title: node.name,
    key: node.key,
    icon: node.nodeType === 'DIRECTORY'
      ? <Folder size={16} />
      : node.mimeType?.startsWith('image/')
        ? <FileImage size={16} />
        : <FileText size={16} />,
    children: toAntTree(node.children)
  }))
}

/** 搜索时保留命中节点的完整父目录，避免返回一组失去层级语义的文件。 */
function filterTree(nodes: WorkspaceTreeNode[], keyword: string): WorkspaceTreeNode[] {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase()
  if (!normalizedKeyword) return nodes
  return nodes.flatMap(node => {
    const children = filterTree(node.children, normalizedKeyword)
    const matched = node.name.toLocaleLowerCase().includes(normalizedKeyword)
      || node.path.toLocaleLowerCase().includes(normalizedKeyword)
    return matched || children.length > 0 ? [{ ...node, children }] : []
  })
}

/** 根据 Tree 选中 key 反查后端节点，用于获取 fileId、MIME 和显示名称。 */
function findTreeNode(nodes: WorkspaceTreeNode[], key: string): WorkspaceTreeNode | undefined {
  for (const node of nodes) {
    if (node.key === key) return node
    const child = findTreeNode(node.children, key)
    if (child) return child
  }
  return undefined
}

/** 保存成功后只替换对应文件节点，保留目录展开和搜索状态。 */
function updateTreeFile(nodes: WorkspaceTreeNode[], fileId: number, updates: Partial<WorkspaceTreeNode>): WorkspaceTreeNode[] {
  return nodes.map(node => node.fileId === fileId
    ? { ...node, ...updates }
    : node.children.length > 0 ? { ...node, children: updateTreeFile(node.children, fileId, updates) } : node)
}

/** 只把明确可安全展示的文本和位图交给预览组件，其他二进制文件提供下载入口。 */
function previewKindOf(node?: WorkspaceTreeNode): PreviewKind {
  if (!node || node.nodeType !== 'FILE') return 'none'
  const extension = node.name.split('.').pop()?.toLocaleLowerCase()
  if (extension === 'md' || extension === 'markdown') return 'markdown'
  if (extension === 'html' || extension === 'htm') return 'html'
  if (node.mimeType && ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/bmp'].includes(node.mimeType)) return 'image'
  if (node.mimeType?.startsWith('text/') || ['json', 'yaml', 'yml', 'xml', 'toml', 'properties', 'js', 'ts', 'tsx', 'jsx', 'css', 'py', 'java', 'sql', 'sh'].includes(extension ?? '')) return 'text'
  return 'binary'
}

/** 根据文件名选择 Monaco 语言模型；当前先使用纯文本模型保证编辑和保存稳定。 */
function editorLanguage(filename: string): string {
  // 后续可按扩展名加载语言服务，本阶段统一使用纯文本模型以控制首屏包体和 Worker 数量。
  void filename
  return 'plaintext'
}

/** 根据 Markdown AST 的源码行范围标记当前编辑块，预览区只高亮最接近正文的元素。 */
function editingBlockClass(
  node: { position?: { start: { line: number }; end: { line: number } } } | undefined,
  activeLine: number | undefined,
  className?: string
): string | undefined {
  const editing = activeLine != null && node?.position != null
    && activeLine >= node.position.start.line
    && activeLine <= node.position.end.line
  return [className, editing ? 'editing-block' : ''].filter(Boolean).join(' ') || undefined
}

/** 为 HTML 预览注入严格 CSP；保留内联样式和 data/blob 图片，但禁止脚本、表单和外部网络请求。 */
function htmlPreviewDocument(content: string): string {
  const policy = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:;">`
  if (/<head[\s>]/i.test(content)) return content.replace(/<head([^>]*)>/i, `<head$1>${policy}`)
  if (/<html[\s>]/i.test(content)) return content.replace(/<html([^>]*)>/i, `<html$1><head>${policy}</head>`)
  return `<!doctype html><html><head>${policy}</head><body>${content}</body></html>`
}

/** 统一复用矢量品牌标志，避免侧栏、登录页和浏览器图标出现不同版本。 */
function BrandLogo({ small = false }: { small?: boolean }) {
  return <img className={`brand-logo${small ? ' small' : ''}`} src="/skillforge-logo.svg" alt="" />
}

/** 登录与注册共用一张表单，提交时根据当前模式构造不同的后端请求。 */
function LoginPage({ onAuthenticated }: { onAuthenticated: (user: CurrentUser) => void }) {
  const { message } = AntApp.useApp()
  const [register, setRegister] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (values: LoginPayload & RegisterPayload) => {
    setSubmitting(true)
    try {
      const user = register
        ? await authApi.register({ username: values.username, email: values.email, password: values.password })
        : await authApi.login({ account: values.account, password: values.password })
      message.success(register ? '账号创建成功' : '登录成功')
      onAuthenticated(user)
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  return <div className="auth-shell">
    <div className="auth-brand">
      <BrandLogo />
      <div><Title level={2}>SkillForge Studio</Title><Text type="secondary">AI skill 创作工作台</Text></div>
    </div>
    <Card className="auth-card" variant="borderless">
      <Text className="eyebrow">{register ? '创建账号' : '欢迎回来'}</Text>
      <Title level={3}>{register ? '开始管理你的 skill' : '进入创作工作台'}</Title>
      <Form layout="vertical" onFinish={handleSubmit} requiredMark="optional">
        {register && <Form.Item label="邮箱" name="email" rules={[{ required: true, message: '请输入邮箱' }, { type: 'email', message: '邮箱格式不正确' }]}><Input size="large" autoComplete="email" placeholder="name@example.com" /></Form.Item>}
        <Form.Item label={register ? '用户名' : '账号'} name={register ? 'username' : 'account'} rules={[{ required: true, message: register ? '请输入用户名' : '请输入账号' }]}><Input size="large" autoComplete="username" placeholder={register ? '输入用户名' : '用户名或邮箱'} /></Form.Item>
        <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }, { min: 8, message: '密码至少 8 个字符' }]}><Input.Password size="large" autoComplete={register ? 'new-password' : 'current-password'} placeholder="输入密码" /></Form.Item>
        <Button loading={submitting} type="primary" htmlType="submit" size="large" block icon={<ArrowUpRight size={16} />}>{register ? '创建账号' : '登录工作台'}</Button>
      </Form>
      <Divider plain>或</Divider>
      <Tooltip title="GitHub OAuth 尚未启用">
        <span className="block-control"><Button size="large" block disabled icon={<Github size={16} />}>使用 GitHub 授权登录</Button></span>
      </Tooltip>
      <div className="auth-switch">{register ? '已经有账号？' : '还没有账号？'}<Button type="link" onClick={() => setRegister(!register)}>{register ? '返回登录' : '立即注册'}</Button></div>
    </Card>
  </div>
}

/** 文件内容组件区分源码、Markdown 渲染和位图，不尝试解析未知二进制格式。 */
function FileContent({ kind, content, imageUrl, presentation, filename, editable = false, onChange, onCursorLineChange, onScrollRatioChange, scrollRatio, activeEditorLine }: {
  kind: PreviewKind
  content: string
  imageUrl?: string
  presentation: 'source' | 'rendered'
  filename: string
  editable?: boolean
  onChange?: (value: string) => void
  onCursorLineChange?: (lineNumber: number) => void
  onScrollRatioChange?: (ratio: number) => void
  scrollRatio?: number
  activeEditorLine?: number
}) {
  if (kind === 'image' && imageUrl) {
    return <div className="image-preview"><img src={imageUrl} alt={filename} /></div>
  }
  if (kind === 'binary') {
    return <div className="pane-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此文件仅支持下载" /></div>
  }
  if (presentation === 'source') {
    return <Suspense fallback={<div className="pane-empty"><Spin /></div>}>
      <LocalEditor value={content} language={editorLanguage(filename)} readOnly={!editable} onChange={onChange} onCursorLineChange={onCursorLineChange} onScrollRatioChange={onScrollRatioChange} scrollRatio={scrollRatio} />
    </Suspense>
  }
  if (kind === 'html') {
    return <iframe className="html-preview" title={`${filename} 预览`} sandbox="" srcDoc={htmlPreviewDocument(content)} />
  }
  if (kind === 'markdown') {
    const markdownComponents: Components = {
      p: ({ node, className, ...props }) => <p {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h1: ({ node, className, ...props }) => <h1 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h2: ({ node, className, ...props }) => <h2 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h3: ({ node, className, ...props }) => <h3 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h4: ({ node, className, ...props }) => <h4 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h5: ({ node, className, ...props }) => <h5 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      h6: ({ node, className, ...props }) => <h6 {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      li: ({ node, className, ...props }) => <li {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      tr: ({ node, className, ...props }) => <tr {...props} className={editingBlockClass(node, activeEditorLine, className)} />,
      pre: ({ node, className, ...props }) => <pre {...props} className={editingBlockClass(node, activeEditorLine, className)} />
    }
    return <div className="markdown-preview"><ReactMarkdown components={markdownComponents} remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw, rehypeSanitize]}>{content}</ReactMarkdown></div>
  }
  return <pre className="plain-text-preview">{content}</pre>
}

function Dashboard({ user, onLogout }: { user: CurrentUser; onLogout: () => void }) {
  const { message, modal } = AntApp.useApp()
  const screens = useBreakpoint()
  const compactNavigation = screens.lg === false
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [fileTreeOpen, setFileTreeOpen] = useState(false)
  const [fileInfoOpen, setFileInfoOpen] = useState(false)
  const [activePage, setActivePage] = useState<AppPage>('home')
  const [viewMode, setViewMode] = useState<ViewMode>('分屏')
  const [activeEditorLine, setActiveEditorLine] = useState<number>()
  const [editorScrollRatio, setEditorScrollRatio] = useState(0)
  const [content, setContent] = useState('')
  const [savedContent, setSavedContent] = useState('')
  const [importOpen, setImportOpen] = useState(false)
  const [importMode, setImportMode] = useState<ImportMode>('目录')
  const [selectedKey, setSelectedKey] = useState('')
  const [selectedFile, setSelectedFile] = useState<WorkspaceTreeNode>()
  const [selectedKind, setSelectedKind] = useState<PreviewKind>('none')
  const [selectedImageUrl, setSelectedImageUrl] = useState<string>()
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([])
  const [currentWorkspaceId, setCurrentWorkspaceId] = useState<string>()
  const [workspaceTree, setWorkspaceTree] = useState<WorkspaceTreeNode[]>([])
  const [workspaceName, setWorkspaceName] = useState('')
  const [importFiles, setImportFiles] = useState<LocalImportFile[]>([])
  const [selectingDirectory, setSelectingDirectory] = useState(false)
  const [draggingImport, setDraggingImport] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [workspacesLoading, setWorkspacesLoading] = useState(true)
  const [treeLoading, setTreeLoading] = useState(false)
  const [fileLoading, setFileLoading] = useState(false)
  const [importing, setImporting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameWorkspaceId, setRenameWorkspaceId] = useState<string>()
  const [renameName, setRenameName] = useState('')
  const [renaming, setRenaming] = useState(false)
  const [githubConnection, setGitHubConnection] = useState<GitHubConnectionView>()
  const [githubConnectionLoading, setGitHubConnectionLoading] = useState(false)
  const [githubConnecting, setGitHubConnecting] = useState(false)
  const [githubDisconnecting, setGitHubDisconnecting] = useState(false)
  const [githubRepositories, setGitHubRepositories] = useState<GitHubRepositoryView[]>([])
  const [githubRepositoriesLoading, setGitHubRepositoriesLoading] = useState(false)
  const [githubRepositorySearch, setGitHubRepositorySearch] = useState('')
  const [selectedGitHubRepository, setSelectedGitHubRepository] = useState<GitHubRepositoryView>()
  const [githubSkills, setGitHubSkills] = useState<GitHubSkillView[]>([])
  const [selectedGitHubSkillPaths, setSelectedGitHubSkillPaths] = useState<string[]>([])
  const [githubSkillModalOpen, setGitHubSkillModalOpen] = useState(false)
  const [githubSkillsLoading, setGitHubSkillsLoading] = useState(false)
  const [githubWorkspaceName, setGitHubWorkspaceName] = useState('')
  const [githubImporting, setGitHubImporting] = useState(false)
  const [githubChangesOpen, setGitHubChangesOpen] = useState(false)
  const [githubChangesLoading, setGitHubChangesLoading] = useState(false)
  const [githubWorkspaceChanges, setGitHubWorkspaceChanges] = useState<GitHubWorkspaceChanges>()
  const selectionRequest = useRef(0)
  const previewPaneRef = useRef<HTMLDivElement | null>(null)
  const scrollSyncSource = useRef<'editor' | 'preview' | null>(null)
  const scrollSyncTimer = useRef<number | undefined>(undefined)
  const previewUserScrolling = useRef(false)
  const previewInteractionTimer = useRef<number | undefined>(undefined)
  const githubCallbackHandled = useRef(false)

  const currentWorkspace = useMemo(
    () => workspaces.find(item => item.id === currentWorkspaceId),
    [workspaces, currentWorkspaceId]
  )
  const filteredTree = useMemo(() => filterTree(workspaceTree, searchText), [workspaceTree, searchText])
  const treeData = useMemo(() => toAntTree(filteredTree), [filteredTree])
  const importTotalSize = useMemo(() => importFiles.reduce((total, item) => total + item.file.size, 0), [importFiles])
  const workspaceTotals = useMemo(() => workspaces.reduce((totals, workspace) => ({
    files: totals.files + workspace.fileCount,
    size: totals.size + workspace.totalSize,
    local: totals.local + (workspace.sourceType === 'LOCAL' ? 1 : 0),
    github: totals.github + (workspace.sourceType === 'GITHUB' ? 1 : 0)
  }), { files: 0, size: 0, local: 0, github: 0 }), [workspaces])
  const filteredGitHubRepositories = useMemo(() => {
    const keyword = githubRepositorySearch.trim().toLocaleLowerCase()
    if (!keyword) return githubRepositories
    return githubRepositories.filter(repository => repository.fullName.toLocaleLowerCase().includes(keyword)
      || repository.description?.toLocaleLowerCase().includes(keyword))
  }, [githubRepositories, githubRepositorySearch])
  const supportsDirectoryPicker = typeof (window as Window & { showDirectoryPicker?: unknown }).showDirectoryPicker === 'function'
  const editableFile = selectedKind === 'markdown' || selectedKind === 'html' || selectedKind === 'text'
  const dirty = editableFile && content !== savedContent

  /** 进入编辑工作台时自动收起桌面导航，为源码和预览保留最大横向空间。 */
  useEffect(() => {
    if (activePage === 'editor' && !compactNavigation) setCollapsed(true)
  }, [activePage, compactNavigation])

  /** 切换工作区时取消旧文件读取结果，并恢复一个明确的未选择状态。 */
  const loadWorkspace = async (workspace: WorkspaceSummary) => {
    const requestId = ++selectionRequest.current
    setTreeLoading(true)
    setCurrentWorkspaceId(workspace.id)
    setSelectedKey('')
    setSelectedFile(undefined)
    setSelectedKind('none')
    setSelectedImageUrl(undefined)
    setActiveEditorLine(undefined)
    setEditorScrollRatio(0)
    setFileTreeOpen(false)
    setFileInfoOpen(false)
    setGitHubChangesOpen(false)
    setGitHubWorkspaceChanges(undefined)
    setContent('')
    setSavedContent('')
    setSearchText('')
    try {
      const tree = await workspaceApi.tree(workspace.id)
      if (selectionRequest.current === requestId) setWorkspaceTree(tree)
    } catch (error) {
      if (selectionRequest.current === requestId) {
        setWorkspaceTree([])
        message.error(getApiErrorMessage(error))
      }
    } finally {
      if (selectionRequest.current === requestId) setTreeLoading(false)
    }
  }

  useEffect(() => {
    let mounted = true
    workspaceApi.list()
      .then(items => {
        if (!mounted) return
        setWorkspaces(items)
        if (items.length > 0) void loadWorkspace(items[0])
      })
      .catch(error => mounted && message.error(getApiErrorMessage(error)))
      .finally(() => mounted && setWorkspacesLoading(false))
    return () => { mounted = false }
  }, [])

  /** 连接状态由后端按当前平台账号查询，接口不会向浏览器返回 Token。 */
  const loadGitHubConnection = async (showError = false) => {
    setGitHubConnectionLoading(true)
    try {
      setGitHubConnection(await githubConnectionApi.status())
    } catch (error) {
      if (showError) message.error(getApiErrorMessage(error))
    } finally {
      setGitHubConnectionLoading(false)
    }
  }

  /** 仓库列表只在用户进入 GitHub 模块或主动刷新时读取，不在普通工作台后台消耗 API 配额。 */
  const loadGitHubRepositories = async (showError = true) => {
    setGitHubRepositoriesLoading(true)
    try {
      setGitHubRepositories(await githubRepositoryApi.list())
    } catch (error) {
      if (showError) message.error(getApiErrorMessage(error))
    } finally {
      setGitHubRepositoriesLoading(false)
    }
  }

  useEffect(() => {
    if (activePage === 'connections') void loadGitHubConnection()
  }, [activePage])

  useEffect(() => {
    if (activePage !== 'github') return
    void loadGitHubConnection()
    if (githubRepositories.length === 0) void loadGitHubRepositories()
  }, [activePage])

  useEffect(() => {
    // OAuth 回调通过短查询参数传递结果；消费后立即清理地址，避免刷新页面重复提示。
    if (githubCallbackHandled.current) return
    const callbackParameters = new URLSearchParams(window.location.search)
    const result = callbackParameters.get('github')
    if (!result) return
    githubCallbackHandled.current = true
    setActivePage('connections')
    if (result === 'connected') {
      message.success('GitHub 连接成功')
    } else {
      const reason = callbackParameters.get('reason')
      const reasonMessages: Record<string, string> = {
        oauth_session_missing: 'OAuth 会话状态已丢失，请重新登录平台后授权',
        token_exchange_failed: 'GitHub 授权码换取 Token 失败，请检查 OAuth App 配置',
        user_info_failed: 'GitHub 账号资料读取失败，请稍后重试',
        platform_session_missing: '平台登录状态已丢失，请重新登录后授权',
        authentication_result_invalid: 'GitHub 认证结果无效，请重新授权',
        authorized_client_missing: 'GitHub Token 未写入授权会话，请重新授权',
        binding_failed: 'GitHub 已授权，但平台账号绑定失败'
      }
      message.error(reasonMessages[reason ?? ''] ?? 'GitHub 授权未完成，请重新尝试')
    }
    window.history.replaceState({}, '', `${window.location.pathname}${window.location.hash}`)
  }, [])

  useEffect(() => {
    /** 浏览器刷新或关闭前使用原生确认，避免尚未保存的文本静默丢失。 */
    const preventAccidentalClose = (event: BeforeUnloadEvent) => {
      if (!dirty) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', preventAccidentalClose)
    return () => window.removeEventListener('beforeunload', preventAccidentalClose)
  }, [dirty])

  /** 将编辑内容恢复到最近一次成功加载或保存的版本，并清除只属于当前编辑过程的定位状态。 */
  const discardUnsavedChanges = () => {
    setContent(savedContent)
    setActiveEditorLine(undefined)
  }

  /**
   * 所有会离开当前编辑上下文的操作统一经过此确认逻辑。
   * 确认放弃时立即回滚内容；继续编辑时明确返回编辑工作台，避免导航状态先行变化。
   */
  const confirmDiscardChanges = (): Promise<boolean> => {
    if (!dirty) return Promise.resolve(true)
    return new Promise(resolve => {
      modal.confirm({
        title: '放弃未保存的修改？',
        content: '当前文件的修改尚未保存，继续操作将丢失这些内容。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        onOk: () => {
          discardUnsavedChanges()
          resolve(true)
        },
        onCancel: () => {
          setActivePage('editor')
          setDrawerOpen(false)
          resolve(false)
        }
      })
    })
  }

  /** 工具栏取消编辑只回滚当前文件，不关闭文件或切换工作区。 */
  const cancelEditing = async () => {
    await confirmDiscardChanges()
  }

  const switchWorkspace = async (workspace: WorkspaceSummary): Promise<boolean> => {
    if (workspace.id === currentWorkspaceId) return true
    if (!await confirmDiscardChanges()) return false
    await loadWorkspace(workspace)
    setDrawerOpen(false)
    return true
  }

  /**
   * 清理本次导入会话产生的临时状态。
   * 弹窗关闭时不会自动卸载，因此需要主动清空文件、名称和拖拽状态，避免下次打开时残留上一次选择。
   */
  const resetImportSession = () => {
    setImportMode('目录')
    setWorkspaceName('')
    setImportFiles([])
    setDraggingImport(false)
  }

  /** 打开前再次重置导入会话，保证所有入口都从相同的空白状态开始。 */
  const openImport = async () => {
    if (!(await confirmDiscardChanges())) return
    resetImportSession()
    setImportOpen(true)
  }

  /** 统一处理取消按钮、右上角关闭按钮和遮罩点击，导入或目录选择进行中时禁止关闭。 */
  const closeImport = () => {
    if (importing || selectingDirectory) return
    resetImportSession()
    setImportOpen(false)
  }

  /** 页面切换必须先处理未保存修改，确认放弃后再执行目标导航。 */
  const navigateTo = async (page: AppPage): Promise<boolean> => {
    if (page === activePage) {
      setDrawerOpen(false)
      return true
    }
    if (!(await confirmDiscardChanges())) return false
    setActivePage(page)
    setDrawerOpen(false)
    return true
  }

  const handleMenu = (key: string) => {
    void navigateTo(key as AppPage)
  }

  /** 退出账号同样保护未保存修改，取消退出时回到当前编辑文件。 */
  const handleLogout = async () => {
    if (await confirmDiscardChanges()) onLogout()
  }

  /** 获取后端校验后的站内 OAuth 入口，再进行整页跳转以完成 GitHub 授权。 */
  const connectGitHub = async () => {
    if (githubConnecting) return
    setGitHubConnecting(true)
    try {
      const result = await githubConnectionApi.authorize()
      window.location.assign(result.authorizationUrl)
    } catch (error) {
      message.error(getApiErrorMessage(error))
      setGitHubConnecting(false)
    }
  }

  /** 解除连接前二次确认，成功后立即刷新为空状态。 */
  const confirmDisconnectGitHub = () => {
    modal.confirm({
      title: '解除 GitHub 连接？',
      content: '平台保存的 GitHub 授权信息和加密访问令牌将被删除。',
      okText: '解除连接',
      cancelText: '取消',
      onOk: async () => {
        setGitHubDisconnecting(true)
        try {
          await githubConnectionApi.disconnect()
          await loadGitHubConnection()
          setGitHubRepositories([])
          message.success('GitHub 连接已解除')
        } catch (error) {
          message.error(getApiErrorMessage(error))
          throw error
        } finally {
          setGitHubDisconnecting(false)
        }
      }
    })
  }

  /** 扫描仓库默认分支中的 SKILL.md，并默认勾选全部发现项以缩短常见导入流程。 */
  const scanGitHubRepository = async (repository: GitHubRepositoryView) => {
    setSelectedGitHubRepository(repository)
    setGitHubSkills([])
    setSelectedGitHubSkillPaths([])
    setGitHubWorkspaceName(repository.name)
    setGitHubSkillModalOpen(true)
    setGitHubSkillsLoading(true)
    try {
      const skills = await githubRepositoryApi.skills(repository.fullName, repository.defaultBranch)
      setGitHubSkills(skills)
      setSelectedGitHubSkillPaths(skills.map(skill => skill.directoryPath))
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setGitHubSkillsLoading(false)
    }
  }

  /** 导入成功后刷新工作区集合，并进入管理页让用户明确选择何时打开编辑器。 */
  const importGitHubSkills = async () => {
    if (!selectedGitHubRepository || !githubWorkspaceName.trim()) {
      message.warning('请输入工作区名称')
      return
    }
    if (selectedGitHubSkillPaths.length === 0) {
      message.warning('请至少选择一个 skill')
      return
    }
    setGitHubImporting(true)
    try {
      const result = await githubRepositoryApi.importSkills({
        repositoryFullName: selectedGitHubRepository.fullName,
        branch: selectedGitHubRepository.defaultBranch,
        workspaceName: githubWorkspaceName.trim(),
        skillPaths: selectedGitHubSkillPaths
      })
      const items = await workspaceApi.list()
      setWorkspaces(items)
      const imported = items.find(item => item.id === result.workspaceId)
      if (imported) await loadWorkspace(imported)
      setGitHubSkillModalOpen(false)
      setActivePage('workspaces')
      message.success(`已从 GitHub 导入 ${result.fileCount} 个文件`)
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setGitHubImporting(false)
    }
  }

  /**
   * 变更比较只对当前 GitHub 工作区开放；面板先打开再加载，让慢速 GitHub 请求有明确反馈。
   * 比较接口不写远端，失败时保留当前编辑状态并通过统一消息提示原因。
   */
  const loadGitHubWorkspaceChanges = async (workspaceId: string) => {
    setGitHubChangesLoading(true)
    try {
      setGitHubWorkspaceChanges(await githubWorkspaceChangesApi.compare(workspaceId))
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setGitHubChangesLoading(false)
    }
  }

  const openGitHubWorkspaceChanges = () => {
    if (!currentWorkspaceId || currentWorkspace?.sourceType !== 'GITHUB') return
    setGitHubChangesOpen(true)
    setGitHubWorkspaceChanges(undefined)
    void loadGitHubWorkspaceChanges(currentWorkspaceId)
  }

  /** 提交成功后刷新工作区树和远端比较结果，确保编辑器立即回到干净状态。 */
  const commitGitHubWorkspace = async (commitMessage: string) => {
    if (!currentWorkspaceId || !currentWorkspace) return
    try {
      const result = await githubWorkspaceChangesApi.commit(currentWorkspaceId, commitMessage)
      await loadWorkspace(currentWorkspace)
      await loadGitHubWorkspaceChanges(currentWorkspaceId)
      message.success(`已推送 ${result.changedFileCount} 个文件，提交 ${result.commitSha.slice(0, 8)}`)
    } catch (error) {
      message.error(getApiErrorMessage(error))
      throw error
    }
  }

  /** 统一校验本地选择结果，界面只保留可提交的 500 个以内、单个不超过 20 MB 的文件。 */
  const acceptImportFiles = (files: LocalImportFile[], suggestedWorkspaceName?: string) => {
    const oversized = files.filter(item => item.file.size > 20 * 1024 * 1024).length
    const accepted = files.filter(item => item.file.size <= 20 * 1024 * 1024).slice(0, 500)
    if (files.length > 500) message.warning('单次最多导入 500 个文件，超出部分未加入清单')
    if (oversized > 0) message.warning(`${oversized} 个超过 20 MB 的文件未加入清单`)
    setImportFiles(accepted)
    if (!workspaceName.trim() && suggestedWorkspaceName) setWorkspaceName(suggestedWorkspaceName)
  }

  /** Chrome/Edge 使用目录句柄读取文件，避免浏览器再次弹出不可定制的“上传全部文件”确认框。 */
  const chooseDirectory = async () => {
    const picker = (window as Window & {
      showDirectoryPicker?: (options?: { mode: 'read' }) => Promise<BrowserDirectoryHandle>
    }).showDirectoryPicker
    if (!picker) return
    setSelectingDirectory(true)
    try {
      const directory = await picker.call(window, { mode: 'read' })
      const files: LocalImportFile[] = []
      await collectDirectoryFiles(directory, directory.name, files)
      acceptImportFiles(files.sort((left, right) => left.relativePath.localeCompare(right.relativePath)), directory.name)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      message.error('无法读取所选目录，请重新选择')
    } finally {
      setSelectingDirectory(false)
    }
  }

  /** Ant Upload 仅负责调用系统文件选择器，选择结果立即转成平台自己的清单模型。 */
  const acceptUploadBatch = (file: RcFile, batch: RcFile[]) => {
    if (file.uid === batch[0]?.uid) {
      const selections = batch.map(item => {
        const relativePath = item.webkitRelativePath || item.name
        return { uid: item.uid, file: item as File, relativePath }
      })
      const suggestedName = selections[0]?.relativePath.split('/')[0]
      acceptImportFiles(selections, importMode === '目录' ? suggestedName : undefined)
    }
    return Upload.LIST_IGNORE
  }

  /** 拖拽文件直接读取 DataTransfer；目录优先使用浏览器提供的文件系统句柄递归展开。 */
  const handleImportDrop = async (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDraggingImport(false)
    if (importMode === '文件') {
      const files = Array.from(event.dataTransfer.files).map(file => ({
        uid: `${file.name}-${file.lastModified}-${file.size}`,
        file,
        relativePath: file.name
      }))
      acceptImportFiles(files)
      return
    }

    const entries = await Promise.all(Array.from(event.dataTransfer.items).map(item => {
      const itemWithHandle = item as DataTransferItem & {
        getAsFileSystemHandle?: () => Promise<BrowserFileHandle | BrowserDirectoryHandle | null>
      }
      return itemWithHandle.getAsFileSystemHandle?.() ?? Promise.resolve(null)
    }))
    const directories = entries.filter((entry): entry is BrowserDirectoryHandle => entry?.kind === 'directory')
    if (directories.length === 0) {
      message.warning('目录模式请拖入一个文件夹')
      return
    }
    const files: LocalImportFile[] = []
    for (const directory of directories) await collectDirectoryFiles(directory, directory.name, files)
    acceptImportFiles(files.sort((left, right) => left.relativePath.localeCompare(right.relativePath)), directories[0].name)
  }

  /** 导入请求直接使用已经校验的文件清单和相对路径，不再依赖上传组件的临时内部状态。 */
  const handleImport = async () => {
    if (!workspaceName.trim()) {
      message.warning('请输入工作区名称')
      return
    }
    if (importFiles.length === 0) {
      message.warning(`请至少选择一个${importMode}`)
      return
    }

    setImporting(true)
    try {
      const result = await workspaceApi.importLocal(
        workspaceName.trim(),
        importFiles.map(item => item.file),
        importFiles.map(item => item.relativePath)
      )
      const items = await workspaceApi.list()
      setWorkspaces(items)
      const imported = items.find(item => item.id === result.workspaceId)
      if (imported) {
        await loadWorkspace(imported)
      }
      setActivePage('workspaces')
      setImportOpen(false)
      resetImportSession()
      message.success(`已导入 ${result.fileCount} 个文件，可在工作区管理中打开`)
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setImporting(false)
    }
  }

  /** 文件请求使用递增序号防止快速切换时旧响应覆盖新选择。 */
  const handleTreeSelect = async (keys: React.Key[]) => {
    if (!keys[0] || !currentWorkspaceId) return
    const key = String(keys[0])
    const node = findTreeNode(workspaceTree, key)
    if (!node || node.nodeType !== 'FILE' || node.fileId == null) return
    if (key === selectedKey || !await confirmDiscardChanges()) return

    const requestId = ++selectionRequest.current
    const kind = previewKindOf(node)
    setSelectedKey(key)
    setSelectedFile(node)
    setSelectedKind(kind)
    setFileTreeOpen(false)
    setActiveEditorLine(undefined)
    setEditorScrollRatio(0)
    setContent('')
    setSavedContent('')
    setSelectedImageUrl(undefined)
    setFileLoading(false)

    if (kind === 'image') {
      setViewMode('预览')
      setSelectedImageUrl(workspaceApi.fileContentUrl(currentWorkspaceId, node.fileId))
      return
    }
    if (kind === 'binary') return

    setViewMode(kind === 'markdown' || kind === 'html' ? '分屏' : '源码')
    setFileLoading(true)
    try {
      const fileContent = await workspaceApi.readTextFile(currentWorkspaceId, node.fileId)
      if (selectionRequest.current === requestId) {
        setContent(fileContent)
        setSavedContent(fileContent)
      }
    } catch (error) {
      if (selectionRequest.current === requestId) message.error(getApiErrorMessage(error))
    } finally {
      if (selectionRequest.current === requestId) setFileLoading(false)
    }
  }

  const openSelectedFile = () => {
    if (!currentWorkspaceId || selectedFile?.fileId == null) return
    window.open(workspaceApi.fileContentUrl(currentWorkspaceId, selectedFile.fileId), '_blank', 'noopener,noreferrer')
  }

  /** 保存时携带文件加载时的哈希；若磁盘内容已变化，后端返回冲突而不会覆盖新版本。 */
  const saveSelectedFile = async () => {
    if (saving || !dirty || !currentWorkspaceId || selectedFile?.fileId == null || !selectedFile.sha256) return
    const requestId = selectionRequest.current
    const savingContent = content
    const savingFileId = selectedFile.fileId
    setSaving(true)
    try {
      const result = await workspaceApi.saveTextFile(currentWorkspaceId, savingFileId, savingContent, selectedFile.sha256)
      const fileUpdates = { sizeBytes: result.sizeBytes, sha256: result.sha256 }
      setSelectedFile(current => current?.fileId === result.fileId ? { ...current, ...fileUpdates } : current)
      setWorkspaceTree(current => updateTreeFile(current, result.fileId, fileUpdates))
      setWorkspaces(current => current.map(workspace => workspace.id === currentWorkspaceId
        ? { ...workspace, totalSize: result.workspaceTotalSize, updatedAt: new Date().toISOString() }
        : workspace))
      if (selectionRequest.current === requestId) setSavedContent(savingContent)
      setGitHubWorkspaceChanges(undefined)
      message.success('文件已保存')
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  useEffect(() => {
    /** Web 编辑器沿用常见的 Ctrl/Cmd + S 操作，并阻止浏览器保存网页。 */
    const handleSaveShortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 's') {
        event.preventDefault()
        void saveSelectedFile()
      }
    }
    window.addEventListener('keydown', handleSaveShortcut)
    return () => window.removeEventListener('keydown', handleSaveShortcut)
  }, [saving, dirty, content, currentWorkspaceId, selectedFile])

  const holdScrollSync = (source: 'editor' | 'preview', duration = 100) => {
    scrollSyncSource.current = source
    if (scrollSyncTimer.current != null) window.clearTimeout(scrollSyncTimer.current)
    scrollSyncTimer.current = window.setTimeout(() => { scrollSyncSource.current = null }, duration)
  }

  /** 只有滚轮或指针操作产生的预览滚动才允许反向同步 Monaco。 */
  const markPreviewUserScrolling = (duration = 360) => {
    previewUserScrolling.current = true
    if (previewInteractionTimer.current != null) window.clearTimeout(previewInteractionTimer.current)
    previewInteractionTimer.current = window.setTimeout(() => { previewUserScrolling.current = false }, duration)
  }

  useEffect(() => {
    if (activeEditorLine == null || viewMode !== '分屏') return
    const frame = window.requestAnimationFrame(() => {
      const preview = previewPaneRef.current
      const editingBlock = preview?.querySelector<HTMLElement>('.editing-block')
      if (!preview || !editingBlock) return
      const previewBounds = preview.getBoundingClientRect()
      const blockBounds = editingBlock.getBoundingClientRect()
      if (blockBounds.top >= previewBounds.top + 48 && blockBounds.bottom <= previewBounds.bottom - 16) return
      holdScrollSync('editor', 180)
      // 只移动预览容器且不使用平滑动画，避免动画结束后的滚动事件反向推动编辑器。
      preview.scrollTop += blockBounds.top - previewBounds.top - (preview.clientHeight - blockBounds.height) / 2
    })
    return () => window.cancelAnimationFrame(frame)
  }, [activeEditorLine, viewMode])

  /** 编辑器和预览区使用滚动比例双向同步，锁标记防止程序化滚动形成循环。 */
  const syncPreviewFromEditor = (ratio: number) => {
    if (scrollSyncSource.current === 'preview') return
    const preview = previewPaneRef.current
    if (!preview) return
    holdScrollSync('editor')
    preview.scrollTop = Math.max(0, preview.scrollHeight - preview.clientHeight) * ratio
  }

  const syncEditorFromPreview = () => {
    if (scrollSyncSource.current === 'editor' || !previewUserScrolling.current || !previewPaneRef.current) return
    const preview = previewPaneRef.current
    const maxScrollTop = Math.max(0, preview.scrollHeight - preview.clientHeight)
    markPreviewUserScrolling()
    holdScrollSync('preview')
    setEditorScrollRatio(maxScrollTop === 0 ? 0 : preview.scrollTop / maxScrollTop)
  }

  const openRename = (workspace: WorkspaceSummary) => {
    setRenameWorkspaceId(workspace.id)
    setRenameName(workspace.name)
    setRenameOpen(true)
  }

  /** 重命名只更新工作区显示信息，不重新加载文件树或中断当前编辑状态。 */
  const renameWorkspace = async () => {
    if (!renameWorkspaceId || !renameName.trim()) return
    setRenaming(true)
    try {
      const renamed = await workspaceApi.rename(renameWorkspaceId, renameName.trim())
      setWorkspaces(current => current.map(workspace => workspace.id === renamed.id ? renamed : workspace))
      setRenameOpen(false)
      message.success('工作区已重命名')
    } catch (error) {
      message.error(getApiErrorMessage(error))
    } finally {
      setRenaming(false)
    }
  }

  /** 删除前二次确认；删除当前工作区后自动打开下一个可用工作区。 */
  const confirmDeleteWorkspace = async (workspace: WorkspaceSummary) => {
    if (workspace.id === currentWorkspaceId && !await confirmDiscardChanges()) return
    modal.confirm({
      title: `删除工作区“${workspace.name}”？`,
      content: '平台中的文件索引和开发环境存储副本将一并删除，此操作无法撤销。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await workspaceApi.remove(workspace.id)
          const remaining = workspaces.filter(item => item.id !== workspace.id)
          setWorkspaces(remaining)
          if (workspace.id === currentWorkspaceId) {
            if (remaining[0]) await loadWorkspace(remaining[0])
            else {
              ++selectionRequest.current
              setCurrentWorkspaceId(undefined)
              setWorkspaceTree([])
              setSelectedFile(undefined)
              setSelectedKey('')
              setContent('')
              setSavedContent('')
            }
          }
          message.success('工作区已删除')
        } catch (error) {
          message.error(getApiErrorMessage(error))
          throw error
        }
      }
    })
  }

  /** 侧栏内容在桌面 Sider 和窄屏 Drawer 中复用，两个容器不会同时显示。 */
  const navigation = <div className="navigation-inner">
    <div className="sider-brand"><BrandLogo small /><span>SkillForge</span></div>
    <Dropdown
      disabled={workspaces.length === 0}
      trigger={['click']}
      menu={{
        selectable: true,
        selectedKeys: currentWorkspaceId ? [currentWorkspaceId] : [],
        items: workspaces.map(workspace => ({ key: workspace.id, label: workspace.name })),
        onClick: ({ key }) => {
          const workspace = workspaces.find(item => item.id === key)
          if (workspace) void switchWorkspace(workspace)
          setDrawerOpen(false)
        }
      }}
    >
      <button type="button" className="workspace-switcher" disabled={workspaces.length === 0}>
        <span className="source-dot" />
        <span className="workspace-switcher-copy">
          <Text strong>{currentWorkspace?.name ?? (workspacesLoading ? '正在加载工作区' : '暂无工作区')}</Text>
          <Text type="secondary">{currentWorkspace?.sourceType === 'GITHUB' ? 'GitHub 工作区' : '本地工作区'}</Text>
        </span>
        <ChevronDown size={15} />
      </button>
    </Dropdown>
    <Menu mode="inline" selectedKeys={[activePage]} items={menuItems} onClick={({ key }) => handleMenu(key)} />
    <div className="sider-bottom"><Text type="secondary">SkillForge Studio · v0.1.0</Text></div>
  </div>

  const renderEditor = () => {
    if (!selectedFile) return <div className="pane-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="从资源目录选择文件" /></div>
    if (fileLoading) return <div className="pane-empty"><Spin /></div>
    if (selectedKind !== 'markdown' && selectedKind !== 'html') {
      return <div className="single-pane"><FileContent kind={selectedKind} content={content} imageUrl={selectedImageUrl} presentation={selectedKind === 'text' ? 'source' : 'rendered'} filename={selectedFile.name} editable={selectedKind === 'text'} onChange={setContent} /></div>
    }
    if (viewMode === '源码') {
      return <div className="editor-pane"><FileContent kind={selectedKind} content={content} presentation="source" filename={selectedFile.name} editable onChange={setContent} /></div>
    }
    if (viewMode === '预览') {
      return <div className="preview-pane"><FileContent kind={selectedKind} content={content} presentation="rendered" filename={selectedFile.name} /></div>
    }
    return <>
      <div className="editor-pane"><FileContent kind={selectedKind} content={content} presentation="source" filename={selectedFile.name} editable onChange={setContent} onCursorLineChange={setActiveEditorLine} onScrollRatioChange={syncPreviewFromEditor} scrollRatio={editorScrollRatio} /></div>
      <div className="splitter" />
      <div
        className="preview-pane"
        ref={previewPaneRef}
        onWheel={() => markPreviewUserScrolling()}
        onPointerDown={() => markPreviewUserScrolling(1000)}
        onScroll={syncEditorFromPreview}
      >
        <FileContent kind={selectedKind} content={content} presentation="rendered" filename={selectedFile.name} activeEditorLine={selectedKind === 'markdown' ? activeEditorLine : undefined} />
      </div>
    </>
  }

  /** 文件信息按需显示在抽屉中，避免长期占用主编辑器横向空间。 */
  const fileInformation = selectedFile ? <>
    <div className="file-meta">
      <div className="file-meta-icon">{selectedKind === 'image' ? <FileImage size={22} /> : <FileText size={22} />}</div>
      <Title level={5}>{selectedFile.name}</Title>
      <Text type="secondary">{selectedKind === 'markdown' ? 'Markdown 文档' : selectedKind === 'html' ? 'HTML 文档' : selectedKind === 'image' ? '图片文件' : selectedKind === 'text' ? '文本文件' : '二进制文件'}</Text>
    </div>
    <Divider />
    <div className="meta-row"><Text type="secondary">大小</Text><Text>{formatBytes(selectedFile.sizeBytes)}</Text></div>
    <div className="meta-row"><Text type="secondary">MIME</Text><Text ellipsis={{ tooltip: selectedFile.mimeType }}>{selectedFile.mimeType ?? '--'}</Text></div>
    <div className="meta-row path-row"><Text type="secondary">路径</Text><Text ellipsis={{ tooltip: selectedFile.path }}>{selectedFile.path}</Text></div>
    <div className="meta-row"><Text type="secondary">SHA-256</Text><Text ellipsis={{ tooltip: selectedFile.sha256 }}>{selectedFile.sha256?.slice(0, 12) ?? '--'}</Text></div>
    <div className="meta-row"><Text type="secondary">工作区更新</Text><Text>{formatDate(currentWorkspace?.updatedAt)}</Text></div>
  </> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未选择文件" />

  /** 文件树仅在用户需要切换文件时打开，选择文件后自动关闭并回到编辑器。 */
  const fileTreeContent = <div className="drawer-file-tree">
    <Input value={searchText} onChange={event => setSearchText(event.target.value)} allowClear prefix={<Search size={15} />} placeholder="搜索文件" className="pill-input" />
    <div className="tree-scroll">
      {treeLoading ? <div className="pane-empty"><Spin /></div> : treeData.length > 0
        ? <Tree
            key={`${currentWorkspaceId}-${searchText}`}
            showIcon
            defaultExpandAll={Boolean(searchText.trim())}
            defaultExpandedKeys={searchText.trim() ? undefined : workspaceTree.map(node => node.key)}
            treeData={treeData}
            selectedKeys={[selectedKey]}
            onSelect={handleTreeSelect}
          />
        : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={searchText ? '没有匹配文件' : '请先导入本地目录或文件'} />}
    </div>
  </div>

  const homePage = <HomePage
    workspaces={workspaces}
    loading={workspacesLoading}
    totals={workspaceTotals}
    onImport={() => void openImport()}
    onViewAll={() => void navigateTo('workspaces')}
    onOpenWorkspace={workspace => void switchWorkspace(workspace).then(switched => switched && navigateTo('editor'))}
  />

  const editorPage = <EditorPage
    workspace={currentWorkspace}
    fileTreeOpen={fileTreeOpen}
    selectedFile={selectedFile}
    selectedKind={selectedKind}
    viewMode={viewMode}
    dirty={dirty}
    saving={saving}
    editorContent={renderEditor()}
    onOpenFiles={() => setFileTreeOpen(true)}
    onViewModeChange={setViewMode}
    onShowFileInfo={() => setFileInfoOpen(true)}
    onDownload={openSelectedFile}
    onCancelEditing={() => void cancelEditing()}
    onSave={() => void saveSelectedFile()}
  />

  const workspacesPage = <WorkspacesPage
    workspaces={workspaces}
    loading={workspacesLoading}
    currentWorkspaceId={currentWorkspaceId}
    onImport={() => void openImport()}
    onOpen={workspace => void switchWorkspace(workspace).then(switched => switched && navigateTo('editor'))}
    onRename={openRename}
    onDelete={workspace => void confirmDeleteWorkspace(workspace)}
  />

  const connectionsPage = <ConnectionsPage
    connection={githubConnection}
    loading={githubConnectionLoading}
    connecting={githubConnecting}
    disconnecting={githubDisconnecting}
    onChooseRepository={() => void navigateTo('github')}
    onDisconnect={confirmDisconnectGitHub}
    onConnect={() => void connectGitHub()}
  />

  const githubPage = <GitHubRepositoriesPage
    connection={githubConnection}
    connectionLoading={githubConnectionLoading}
    repositories={githubRepositories}
    filteredRepositories={filteredGitHubRepositories}
    repositoriesLoading={githubRepositoriesLoading}
    search={githubRepositorySearch}
    onSearchChange={setGitHubRepositorySearch}
    onRefresh={() => void loadGitHubRepositories()}
    onOpenConnections={() => void navigateTo('connections')}
    onScan={repository => void scanGitHubRepository(repository)}
  />

  const profilePage = <ProfilePage user={user} onOpenConnections={() => void navigateTo('connections')} onLogout={() => void handleLogout()} />

  const pageContent: Record<AppPage, React.ReactNode> = {
    home: homePage,
    editor: editorPage,
    workspaces: workspacesPage,
    connections: connectionsPage,
    github: githubPage,
    profile: profilePage
  }

  return <Layout className={`app-shell${compactNavigation || collapsed ? ' navigation-collapsed' : ''}`}>
    {!compactNavigation && <Sider width={248} collapsedWidth={0} collapsed={collapsed} trigger={null} className="app-sider">{navigation}</Sider>}
    <Drawer width={280} placement="left" open={compactNavigation && drawerOpen} onClose={() => setDrawerOpen(false)} closable={false} styles={{ body: { padding: 0 } }}>
      <div className="mobile-navigation">{navigation}</div>
    </Drawer>
    <Layout>
      <Header className="app-header">
        <div className="header-leading">
          <Tooltip title={compactNavigation ? '打开导航' : collapsed ? '展开导航' : '收起导航'}>
            <Button
              type="text"
              className="icon-button"
              icon={compactNavigation || collapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
              onClick={() => compactNavigation ? setDrawerOpen(true) : setCollapsed(!collapsed)}
            />
          </Tooltip>
          <div className="breadcrumb"><Text type="secondary">SkillForge Studio</Text><ChevronRight size={14} /><Text strong>{pageNames[activePage]}</Text></div>
          {activePage === 'editor' && <div className="editor-header-context">
            <Text strong ellipsis={{ tooltip: currentWorkspace?.name }}>{currentWorkspace?.name ?? '尚未打开工作区'}</Text>
            <Text type="secondary">{currentWorkspace ? `${currentWorkspace.fileCount} 个文件 · ${formatBytes(currentWorkspace.totalSize)}` : '请先导入工作区'}</Text>
          </div>}
        </div>
        <Space>
          {activePage === 'editor' && <Tag className="source-tag header-source" icon={<PanelLeft size={13} />}>{currentWorkspace?.sourceType === 'GITHUB' ? 'GitHub 模式' : '本地模式'}</Tag>}
          {activePage === 'editor' && currentWorkspace?.sourceType === 'GITHUB' && <Tooltip title="比较 GitHub 变更"><Button type="text" className="icon-button" loading={githubChangesLoading} icon={<GitCompareArrows size={17} />} onClick={openGitHubWorkspaceChanges} /></Tooltip>}
          {activePage === 'editor' && <Tooltip title="工作区管理"><Button type="text" className="icon-button" icon={<Folder size={17} />} onClick={() => void navigateTo('workspaces')} /></Tooltip>}
          <Dropdown trigger={['click']} menu={{ items: [
            { key: 'identity', disabled: true, label: <div className="account-menu-identity"><Text strong>{user.username}</Text><Text type="secondary">{user.email}</Text></div> },
            { type: 'divider' },
            { key: 'profile', icon: <CircleUserRound size={16} />, label: '账号资料' },
            { key: 'connections', icon: <Link2 size={16} />, label: '连接管理' },
            { type: 'divider' },
            { key: 'logout', icon: <LogOut size={16} />, label: '退出登录' }
          ], onClick: ({ key }) => key === 'logout' ? void handleLogout() : void navigateTo(key as AppPage) }}>
            <Avatar className="avatar">{user.username.slice(0, 1).toUpperCase()}</Avatar>
          </Dropdown>
        </Space>
      </Header>
      <Content className={`app-content page-${activePage}`}>{pageContent[activePage]}</Content>
      {activePage !== 'editor' && <Footer className="app-footer"><Text type="secondary">© 2026 SkillForge Studio</Text><Text type="secondary">AI skill 创作与工作区管理平台</Text></Footer>}
    </Layout>

    <Drawer
      className="file-tree-drawer"
      title={<div className="panel-title"><span>{currentWorkspace?.name ?? '资源目录'}</span><Tag className="source-tag">{currentWorkspace?.sourceType ?? 'LOCAL'}</Tag></div>}
      width={380}
      placement="left"
      open={fileTreeOpen}
      onClose={() => setFileTreeOpen(false)}
    >
      {fileTreeContent}
    </Drawer>

    <Drawer title="文件信息" width={380} open={fileInfoOpen} onClose={() => setFileInfoOpen(false)}>
      {fileInformation}
    </Drawer>

    <GitHubChangesDrawer
      open={githubChangesOpen}
      loading={githubChangesLoading}
      changes={githubWorkspaceChanges}
      onClose={() => setGitHubChangesOpen(false)}
      onRefresh={() => currentWorkspaceId && void loadGitHubWorkspaceChanges(currentWorkspaceId)}
      onCommit={commitGitHubWorkspace}
    />

    <Modal
      title="重命名工作区"
      open={renameOpen}
      onCancel={() => !renaming && setRenameOpen(false)}
      onOk={() => void renameWorkspace()}
      confirmLoading={renaming}
      okText="保存"
      cancelText="取消"
    >
      <Input value={renameName} onChange={event => setRenameName(event.target.value)} maxLength={120} showCount placeholder="工作区名称" onPressEnter={() => void renameWorkspace()} />
    </Modal>

    <Modal className="import-modal" title="导入本地内容" open={importOpen} onCancel={closeImport} onOk={handleImport} confirmLoading={importing} okText="创建工作区" cancelText="取消" width={720}>
      <Segmented block value={importMode} onChange={value => { setImportMode(value as ImportMode); setImportFiles([]); setWorkspaceName('') }} options={['目录', '文件']} className="import-mode" />
      <Input value={workspaceName} onChange={event => setWorkspaceName(event.target.value)} placeholder="工作区名称" className="import-name-input" maxLength={120} showCount />

      {importFiles.length === 0 ? <div
        className={`import-picker-panel${draggingImport ? ' is-dragging' : ''}`}
        onDragEnter={event => { event.preventDefault(); setDraggingImport(true) }}
        onDragOver={event => { event.preventDefault(); setDraggingImport(true) }}
        onDragLeave={event => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingImport(false)
        }}
        onDrop={event => void handleImportDrop(event)}
      >
        <div className="import-picker-icon">{importMode === '目录' ? <Library size={28} /> : <Files size={28} />}</div>
        <Title level={4}>{draggingImport ? `释放以加入${importMode}` : `拖拽${importMode}到这里`}</Title>
        <Paragraph type="secondary">或通过系统选择器添加，单次最多 500 个文件，单文件最大 20 MB</Paragraph>
        {importMode === '目录' && supportsDirectoryPicker
          ? <Button type="primary" size="large" icon={<Folder size={17} />} loading={selectingDirectory} onClick={() => void chooseDirectory()}>选择目录</Button>
          : <Upload key={importMode} directory={importMode === '目录'} multiple showUploadList={false} beforeUpload={acceptUploadBatch}>
              <Button type="primary" size="large" icon={importMode === '目录' ? <Folder size={17} /> : <Files size={17} />}>选择{importMode}</Button>
            </Upload>}
      </div> : <div className="import-selection-panel">
        <div className="import-selection-summary">
          <div className="import-picker-icon small"><Files size={20} /></div>
          <div><Text strong>已选择 {importFiles.length} 个文件</Text><Text type="secondary">共 {formatBytes(importTotalSize)}</Text></div>
          <Button icon={<Trash2 size={15} />} onClick={() => setImportFiles([])}>更换内容</Button>
        </div>
        <div className="import-file-list">
          {importFiles.slice(0, 10).map(item => <div className="import-file-row" key={item.uid}>
            <FileText size={15} />
            <Text ellipsis={{ tooltip: item.relativePath }}>{item.relativePath}</Text>
            <Text type="secondary">{formatBytes(item.file.size)}</Text>
          </div>)}
          {importFiles.length > 10 && <div className="import-file-more">其余 {importFiles.length - 10} 个文件将在创建时一并导入</div>}
        </div>
      </div>}
    </Modal>

    <Modal
      className="github-skill-modal"
      title={selectedGitHubRepository ? `从 ${selectedGitHubRepository.fullName} 导入` : '导入 GitHub skills'}
      open={githubSkillModalOpen}
      onCancel={() => !githubImporting && setGitHubSkillModalOpen(false)}
      onOk={() => void importGitHubSkills()}
      confirmLoading={githubImporting}
      okButtonProps={{ disabled: githubSkillsLoading || selectedGitHubSkillPaths.length === 0 }}
      okText="创建 GitHub 工作区"
      cancelText="取消"
      width={720}
    >
      <Input className="github-workspace-name" value={githubWorkspaceName} onChange={event => setGitHubWorkspaceName(event.target.value)} maxLength={120} showCount placeholder="工作区名称" />
      <div className="skill-selection-heading">
        <div><Text strong>发现的 skills</Text><Text type="secondary">{selectedGitHubRepository?.defaultBranch ?? '--'} 分支 · 已选择 {selectedGitHubSkillPaths.length} 个</Text></div>
        <Checkbox
          disabled={githubSkills.length === 0}
          indeterminate={selectedGitHubSkillPaths.length > 0 && selectedGitHubSkillPaths.length < githubSkills.length}
          checked={githubSkills.length > 0 && selectedGitHubSkillPaths.length === githubSkills.length}
          onChange={event => setSelectedGitHubSkillPaths(event.target.checked ? githubSkills.map(skill => skill.directoryPath) : [])}
        >全选</Checkbox>
      </div>
      {githubSkillsLoading
        ? <div className="skill-selection-empty"><Spin /></div>
        : githubSkills.length === 0
          ? <div className="skill-selection-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该分支中未发现 SKILL.md" /></div>
          : <Checkbox.Group className="skill-selection-list" value={selectedGitHubSkillPaths} onChange={values => setSelectedGitHubSkillPaths(values as string[])}>
              {githubSkills.map(skill => <Checkbox className="skill-selection-row" value={skill.directoryPath} key={skill.manifestPath}>
                <span className="skill-selection-copy"><Text strong>{skill.name}</Text><Text type="secondary">{skill.manifestPath}</Text></span>
              </Checkbox>)}
            </Checkbox.Group>}
    </Modal>
  </Layout>
}

export default function App() {
  const [user, setUser] = useState<CurrentUser | null | undefined>(undefined)

  useEffect(() => {
    // 页面刷新后通过 HttpSession 恢复用户，不在 localStorage 中保存认证凭据。
    authApi.me().then(setUser).catch(() => setUser(null))
    const handleExpiredSession = () => setUser(null)
    window.addEventListener(AUTH_EXPIRED_EVENT, handleExpiredSession)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleExpiredSession)
  }, [])

  const logout = async () => {
    // 即使网络异常也清理本地用户状态，避免界面继续显示已登录内容。
    try {
      await authApi.logout()
    } finally {
      setUser(null)
    }
  }

  if (user === undefined) {
    return <AntApp><div className="loading-screen"><Spin size="large" /></div></AntApp>
  }
  return <AntApp>{user ? <Dashboard user={user} onLogout={logout} /> : <LoginPage onAuthenticated={setUser} />}</AntApp>
}
