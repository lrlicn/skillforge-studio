import { Button, Card, Empty, Spin, Tag, Typography } from 'antd'
import { ExternalLink, Files, Folder, Github, HardDrive, UploadCloud } from 'lucide-react'
import type { WorkspaceSummary } from '../api/client'
import { formatBytes, formatDate } from '../utils/format'

const { Title, Text, Paragraph } = Typography

export interface HomePageProps {
  workspaces: WorkspaceSummary[]
  loading: boolean
  totals: { files: number; size: number; local: number; github: number }
  onImport: () => void
  onViewAll: () => void
  onOpenWorkspace: (workspace: WorkspaceSummary) => void
}

/** 工作台首页只承载跨工作区指标和最近入口。 */
export default function HomePage({ workspaces, loading, totals, onImport, onViewAll, onOpenWorkspace }: HomePageProps) {
  return <>
    <div className="page-heading">
      <div><Title level={2}>工作台概览</Title><Paragraph type="secondary">查看全部 skill 工作区规模并继续最近的编辑任务。</Paragraph></div>
      <Button type="primary" icon={<UploadCloud size={16} />} onClick={onImport}>导入本地</Button>
    </div>
    <div className="summary-grid">
      <Card variant="borderless"><div className="summary-label"><Folder size={17} /> 工作区</div><div className="summary-value">{workspaces.length}</div><Text type="secondary">当前账号可访问</Text></Card>
      <Card variant="borderless"><div className="summary-label"><Files size={17} /> 文件总数</div><div className="summary-value">{totals.files}</div><Text type="secondary">全部工作区文件</Text></Card>
      <Card variant="borderless"><div className="summary-label"><HardDrive size={17} /> 存储用量</div><div className="summary-value compact-value">{formatBytes(totals.size)}</div><Text type="secondary">开发环境存储副本</Text></Card>
      <Card variant="borderless"><div className="summary-label"><Github size={17} /> 数据来源</div><div className="summary-value source-value"><span>{totals.local} 本地</span><span>{totals.github} GitHub</span></div><Text type="secondary">按来源分类</Text></Card>
    </div>
    <section className="management-section recent-section" aria-label="最近工作区">
      <div className="section-toolbar"><div><Title level={4}>最近工作区</Title><Text type="secondary">按最近更新时间排列</Text></div><Button onClick={onViewAll}>查看全部</Button></div>
      {loading ? <div className="management-empty"><Spin /></div> : workspaces.length === 0
        ? <div className="management-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无工作区" /></div>
        : <div className="recent-workspace-list">{workspaces.slice(0, 5).map(workspace => <div className="recent-workspace-row" key={workspace.id}>
            <div className="workspace-name-cell"><div className="workspace-icon"><Folder size={18} /></div><div><Text strong>{workspace.name}</Text><Text type="secondary">{workspace.fileCount} 个文件 · {formatBytes(workspace.totalSize)}</Text></div></div>
            <Tag className="source-tag" icon={workspace.sourceType === 'GITHUB' ? <Github size={12} /> : <HardDrive size={12} />}>{workspace.sourceType === 'GITHUB' ? 'GitHub' : '本地'}</Tag>
            <Text type="secondary">{formatDate(workspace.updatedAt)}</Text>
            <Button icon={<ExternalLink size={15} />} onClick={() => onOpenWorkspace(workspace)}>打开</Button>
          </div>)}</div>}
    </section>
  </>
}
