import { Button, Empty, Input, Space, Spin, Tag, Tooltip, Typography } from 'antd'
import { ExternalLink, GitBranch, Github, Link2, RefreshCw, Search } from 'lucide-react'
import type { GitHubConnectionView, GitHubRepositoryView } from '../api/client'
import { formatDate } from '../utils/format'

const { Title, Text, Paragraph } = Typography

export interface GitHubRepositoriesPageProps {
  connection?: GitHubConnectionView
  connectionLoading: boolean
  repositories: GitHubRepositoryView[]
  filteredRepositories: GitHubRepositoryView[]
  repositoriesLoading: boolean
  search: string
  onSearchChange: (value: string) => void
  onRefresh: () => void
  onOpenConnections: () => void
  onScan: (repository: GitHubRepositoryView) => void
}

/** GitHub 仓库页负责仓库搜索和 skill 扫描入口，不持有 OAuth Token。 */
export default function GitHubRepositoriesPage(props: GitHubRepositoriesPageProps) {
  return <>
    <div className="page-heading">
      <div><Title level={2}>GitHub 仓库</Title><Paragraph type="secondary">选择已授权仓库，扫描并导入其中的 skills。</Paragraph></div>
      <Button icon={<RefreshCw size={16} />} loading={props.repositoriesLoading} disabled={!props.connection?.connected} onClick={props.onRefresh}>刷新仓库</Button>
    </div>
    <section className="management-section github-repository-section" aria-label="GitHub 仓库列表">
      <div className="section-toolbar github-repository-toolbar">
        <div><Title level={4}>可访问仓库</Title><Text type="secondary">共 {props.repositories.length} 个</Text></div>
        <Input className="repository-search" prefix={<Search size={15} />} allowClear value={props.search} onChange={event => props.onSearchChange(event.target.value)} placeholder="搜索仓库名称或描述" />
      </div>
      {props.connectionLoading && !props.connection
        ? <div className="management-empty"><Spin /></div>
        : !props.connection?.connected
          ? <div className="management-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先连接 GitHub"><Button type="primary" icon={<Link2 size={16} />} onClick={props.onOpenConnections}>前往连接管理</Button></Empty></div>
          : props.repositoriesLoading && props.repositories.length === 0
            ? <div className="management-empty"><Spin /></div>
            : props.filteredRepositories.length === 0
              ? <div className="management-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={props.search ? '没有匹配的仓库' : '当前授权下没有可访问仓库'} /></div>
              : <div className="repository-table">
                  <div className="repository-table-head"><span>仓库</span><span>可见性</span><span>默认分支</span><span>更新时间</span><span>操作</span></div>
                  {props.filteredRepositories.map(repository => <div className="repository-row" key={repository.id}>
                    <div className="repository-name-cell"><div className="workspace-icon"><Github size={18} /></div><div><Text strong>{repository.fullName}</Text><Text type="secondary" ellipsis={{ tooltip: repository.description }}>{repository.description || '暂无描述'}</Text></div></div>
                    <Tag className="source-tag">{repository.privateRepository ? '私有' : '公开'}</Tag>
                    <div className="repository-branch"><GitBranch size={14} /><Text>{repository.defaultBranch}</Text></div>
                    <Text type="secondary">{formatDate(repository.updatedAt)}</Text>
                    <Space>
                      <Tooltip title="在 GitHub 打开"><Button className="icon-button" icon={<ExternalLink size={15} />} onClick={() => window.open(repository.htmlUrl, '_blank', 'noopener,noreferrer')} /></Tooltip>
                      <Button type="primary" icon={<Search size={15} />} onClick={() => props.onScan(repository)}>扫描 skills</Button>
                    </Space>
                  </div>)}
                </div>}
    </section>
  </>
}
