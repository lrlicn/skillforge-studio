import { Button, Drawer, Empty, Input, Modal, Spin, Tag, Tooltip, Typography, message } from 'antd'
import { GitCompareArrows, RefreshCw, Upload } from 'lucide-react'
import { useState } from 'react'
import type { GitHubFileChangeStatus, GitHubWorkspaceChanges } from './types'
import GitHubDiffModal from './GitHubDiffModal'
import './github-changes.css'

const { Text } = Typography

const statusLabels: Record<GitHubFileChangeStatus, string> = {
  LOCAL_MODIFIED: '本地修改',
  REMOTE_MODIFIED: '远端修改',
  REMOTE_DELETED: '远端删除',
  CONFLICT: '存在冲突'
}

export interface GitHubChangesDrawerProps {
  open: boolean
  loading: boolean
  changes?: GitHubWorkspaceChanges
  onClose: () => void
  onRefresh: () => void
  onCommit: (message: string) => Promise<void>
}

/**
 * GitHub 变更面板只负责呈现比较结果，不直接读取全局状态或发起请求。
 * 这种边界让后续提交、推送和同步功能可以复用同一份比较数据。
 */
export default function GitHubChangesDrawer({
  open,
  loading,
  changes,
  onClose,
  onRefresh,
  onCommit
}: GitHubChangesDrawerProps) {
  const [commitOpen, setCommitOpen] = useState(false)
  const [commitMessage, setCommitMessage] = useState('')
  const [committing, setCommitting] = useState(false)
  const [diffPath, setDiffPath] = useState<string>()
  const canCommit = Boolean(changes && changes.baselineAvailable && changes.localChangeCount > 0 && changes.conflictCount === 0 && !changes.remoteAdvanced)

  const submitCommit = async () => {
    if (!commitMessage.trim()) { message.warning('请填写提交说明'); return }
    setCommitting(true)
    try { await onCommit(commitMessage.trim()); setCommitOpen(false); setCommitMessage('') }
    finally { setCommitting(false) }
  }
  return <><Drawer
    className="github-changes-drawer"
    width={560}
    open={open}
    onClose={onClose}
    title={<div className="github-changes-title"><GitCompareArrows size={18} /><span>GitHub 变更</span></div>}
    extra={<Tooltip title="重新比较"><Button className="icon-button" type="text" loading={loading} icon={<RefreshCw size={16} />} onClick={onRefresh} /></Tooltip>}
  >
    {loading && !changes
      ? <div className="github-changes-loading"><Spin /></div>
      : !changes
        ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未获取比较结果" />
        : <div className="github-changes-content">
            <div className="github-changes-actions"><Button type="primary" icon={<Upload size={15} />} disabled={!canCommit} onClick={() => setCommitOpen(true)}>提交并推送</Button><Button icon={<RefreshCw size={15} />} loading={loading} onClick={onRefresh}>重新比较</Button></div>
            <section className="github-changes-repository" aria-label="远端仓库信息">
              <div>
                <Text strong>{changes.repositoryFullName}</Text>
                <Text type="secondary">{changes.repositoryRef} 分支 · 跟踪 {changes.trackedFileCount} 个文件</Text>
              </div>
              <Tag className="source-tag">{changes.remoteAdvanced ? '远端已前进' : '远端未前进'}</Tag>
            </section>

            {!changes.baselineAvailable && <div className="github-changes-notice">
              <Text strong>兼容比较模式</Text>
              <Text type="secondary">该工作区创建于基线功能启用前，本次结果表示本地文件与当前远端是否一致，无法还原导入后的远端变化方向。</Text>
            </div>}
            {changes.baselineAvailable && changes.remoteAdvanced && <div className="github-changes-notice">
              <Text strong>远端分支已有新提交</Text>
              <Text type="secondary">系统已按文件基线识别远端变化；存在冲突时，后续推送必须先处理冲突。</Text>
            </div>}

            <section className="github-changes-summary" aria-label="变更统计">
              <div><Text type="secondary">本地变化</Text><strong>{changes.localChangeCount}</strong></div>
              <div><Text type="secondary">远端变化</Text><strong>{changes.remoteChangeCount}</strong></div>
              <div><Text type="secondary">冲突</Text><strong>{changes.conflictCount}</strong></div>
            </section>

            <section className="github-commit-baseline" aria-label="提交基线">
              <div><Text type="secondary">导入基线</Text><Text code>{shortSha(changes.baseCommitSha)}</Text></div>
              <div><Text type="secondary">远端提交</Text><Text code>{shortSha(changes.remoteCommitSha)}</Text></div>
            </section>

            <div className="github-change-list-heading">
              <Text strong>变更文件</Text>
              <Text type="secondary">共 {changes.changes.length} 个</Text>
            </div>
            {changes.changes.length === 0
              ? <div className="github-changes-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本地文件与远端一致" /></div>
              : <div className="github-change-list">
                  {changes.changes.map(change => <div className="github-change-row" key={change.path}>
                    <div className="github-change-copy">
                      <Text ellipsis={{ tooltip: change.path }}>{change.path}</Text>
                      <Text type="secondary">本地 {shortSha(change.localSha256)} · 远端 {shortSha(change.remoteBlobSha)}</Text>
                    </div>
                    <div className="github-change-actions"><Tag className={`change-status change-status-${change.status.toLowerCase()}`}>{statusLabels[change.status]}</Tag><Button size="small" onClick={() => setDiffPath(change.path)}>查看 Diff</Button></div>
                  </div>)}
                </div>}
          </div>}
    <Modal title="提交并推送" open={commitOpen} confirmLoading={committing} okText="提交并推送" cancelText="取消" onCancel={() => !committing && setCommitOpen(false)} onOk={() => void submitCommit()}>
      <Input.TextArea value={commitMessage} onChange={event => setCommitMessage(event.target.value)} maxLength={200} showCount autoFocus placeholder="例如：更新文章排版说明" rows={4} />
    </Modal>
  </Drawer>
    <GitHubDiffModal open={Boolean(diffPath)} workspaceId={changes?.workspaceId} path={diffPath} onClose={() => setDiffPath(undefined)} />
  </>
}

/** SHA 只用于识别版本，界面展示前 8 位即可，空基线统一显示未记录。 */
function shortSha(sha?: string | null): string {
  return sha ? sha.slice(0, 8) : '未记录'
}
