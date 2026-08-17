import { Button, Dropdown, Empty, Space, Spin, Tag, Tooltip, Typography } from 'antd'
import { ExternalLink, Folder, Github, HardDrive, MoreHorizontal, Pencil, Trash2, UploadCloud } from 'lucide-react'
import type { WorkspaceSummary } from '../api/client'
import { formatBytes, formatDate } from '../utils/format'

const { Title, Text, Paragraph } = Typography

export interface WorkspacesPageProps {
  workspaces: WorkspaceSummary[]
  loading: boolean
  currentWorkspaceId?: string
  onImport: () => void
  onOpen: (workspace: WorkspaceSummary) => void
  onRename: (workspace: WorkspaceSummary) => void
  onDelete: (workspace: WorkspaceSummary) => void
}

/** 工作区页集中提供跨来源工作区的打开、重命名和删除入口。 */
export default function WorkspacesPage(props: WorkspacesPageProps) {
  return <>
    <div className="page-heading">
      <div><Title level={2}>工作区管理</Title><Paragraph type="secondary">集中管理本地导入和 GitHub 来源的工作区。</Paragraph></div>
      <Button type="primary" icon={<UploadCloud size={16} />} onClick={props.onImport}>导入本地工作区</Button>
    </div>
    <section className="management-section" aria-label="工作区列表">
      <div className="section-toolbar"><div><Title level={4}>全部工作区</Title><Text type="secondary">共 {props.workspaces.length} 个</Text></div></div>
      {props.loading ? <div className="management-empty"><Spin /></div> : props.workspaces.length === 0
        ? <div className="management-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无工作区" /></div>
        : <div className="workspace-table">
            <div className="workspace-table-head"><span>名称</span><span>来源</span><span>文件</span><span>大小</span><span>更新时间</span><span>操作</span></div>
            {props.workspaces.map(workspace => <div className={`workspace-row${workspace.id === props.currentWorkspaceId ? ' is-current' : ''}`} key={workspace.id}>
              <div className="workspace-name-cell"><div className="workspace-icon"><Folder size={18} /></div><div><Text strong>{workspace.name}</Text>{workspace.id === props.currentWorkspaceId && <Text type="secondary">当前打开</Text>}</div></div>
              <div><Tag className="source-tag" icon={workspace.sourceType === 'GITHUB' ? <Github size={12} /> : <HardDrive size={12} />}>{workspace.sourceType === 'GITHUB' ? 'GitHub' : '本地'}</Tag></div>
              <Text>{workspace.fileCount}</Text>
              <Text>{formatBytes(workspace.totalSize)}</Text>
              <Text type="secondary">{formatDate(workspace.updatedAt)}</Text>
              <Space>
                <Button icon={<ExternalLink size={15} />} onClick={() => props.onOpen(workspace)}>打开</Button>
                <Dropdown trigger={['click']} menu={{ items: [
                  { key: 'rename', icon: <Pencil size={15} />, label: '重命名' },
                  { key: 'delete', icon: <Trash2 size={15} />, label: '删除', danger: true }
                ], onClick: ({ key }) => key === 'rename' ? props.onRename(workspace) : props.onDelete(workspace) }}>
                  <Tooltip title="更多操作"><Button className="icon-button" icon={<MoreHorizontal size={16} />} /></Tooltip>
                </Dropdown>
              </Space>
            </div>)}
          </div>}
    </section>
  </>
}
