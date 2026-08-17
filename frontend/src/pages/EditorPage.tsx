import type { ReactNode } from 'react'
import { Button, Card, Space, Tag, Tooltip, Typography } from 'antd'
import { Columns2, Download, Eye, FileCode2, FileImage, FileText, FolderOpen, Info, Save, Undo2 } from 'lucide-react'
import type { WorkspaceSummary, WorkspaceTreeNode } from '../api/client'
import type { PreviewKind, ViewMode } from '../features/editor/types'

const { Text } = Typography

export interface EditorPageProps {
  workspace?: WorkspaceSummary
  fileTreeOpen: boolean
  selectedFile?: WorkspaceTreeNode
  selectedKind: PreviewKind
  viewMode: ViewMode
  dirty: boolean
  saving: boolean
  editorContent: ReactNode
  onOpenFiles: () => void
  onViewModeChange: (mode: ViewMode) => void
  onShowFileInfo: () => void
  onDownload: () => void
  onCancelEditing: () => void
  onSave: () => void
}

/** 编辑页只组织编辑器工具栏和主画布，文件读取与保存状态由上层工作区协调器管理。 */
export default function EditorPage(props: EditorPageProps) {
  const supportsRenderedView = props.selectedKind === 'markdown' || props.selectedKind === 'html'
  return <div className="workspace-layout">
    <button type="button" className={`resource-rail${props.fileTreeOpen ? ' is-active' : ''}`} disabled={!props.workspace} onClick={props.onOpenFiles} aria-label="打开资源目录">
      <FolderOpen size={15} />
      <span>资源目录</span>
    </button>
    {supportsRenderedView && <div className="view-mode-float" role="group" aria-label="编辑视图模式">
      {([
        { value: '源码' as ViewMode, label: '源码视图', icon: <FileCode2 size={17} /> },
        { value: '分屏' as ViewMode, label: '分屏视图', icon: <Columns2 size={17} /> },
        { value: '预览' as ViewMode, label: '预览视图', icon: <Eye size={17} /> }
      ]).map(option => <Tooltip title={option.label} key={option.value}>
        <Button type={props.viewMode === option.value ? 'primary' : 'text'} className="view-mode-button" icon={option.icon} aria-label={option.label} aria-pressed={props.viewMode === option.value} onClick={() => props.onViewModeChange(option.value)} />
      </Tooltip>)}
    </div>}
    <Card className="editor-panel" variant="borderless" styles={{ body: { padding: 0 } }}>
      <div className="editor-toolbar">
        <div className="selected-file-title">
          {props.selectedKind === 'image' ? <FileImage size={16} /> : props.selectedKind === 'binary' ? <FileCode2 size={16} /> : <FileText size={16} />}
          <Text strong ellipsis={{ tooltip: props.selectedFile?.name }}>{props.selectedFile?.name ?? '未选择文件'}</Text>
          {props.selectedFile && <Tag className="source-tag">{kindLabel(props.selectedKind)}</Tag>}
          {props.dirty && <Tag className="dirty-tag">未保存</Tag>}
        </div>
        <Space className="editor-actions">
          <Tooltip title="文件信息"><Button disabled={!props.selectedFile} type="text" className="icon-button" icon={<Info size={16} />} onClick={props.onShowFileInfo} /></Tooltip>
          <Tooltip title="下载原文件"><Button disabled={!props.selectedFile} type="text" className="icon-button" icon={<Download size={16} />} onClick={props.onDownload} /></Tooltip>
          <Button icon={<Undo2 size={16} />} disabled={!props.dirty || props.saving} onClick={props.onCancelEditing}>取消编辑</Button>
          <Button type="primary" icon={<Save size={16} />} loading={props.saving} disabled={!props.dirty} onClick={props.onSave}>保存</Button>
        </Space>
      </div>
      <div className={`editor-workspace mode-${props.viewMode}`}>{props.editorContent}</div>
    </Card>
  </div>
}

function kindLabel(kind: PreviewKind): string {
  if (kind === 'markdown') return 'Markdown'
  if (kind === 'html') return 'HTML'
  if (kind === 'image') return '图片'
  if (kind === 'text') return '文本'
  return '文件'
}
