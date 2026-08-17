import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Modal, Segmented, Spin, Tag, Typography, message } from 'antd'
import { Maximize2, Minimize2 } from 'lucide-react'
import { getApiErrorMessage } from '../../api/client'
import { githubWorkspaceChangesApi } from './api'
import type { GitHubFileDiff } from './api'

interface GitHubDiffModalProps {
  open: boolean
  workspaceId?: string
  path?: string
  onClose: () => void
}

interface DiffLine {
  number: number
  value: string
}

interface DiffRow {
  left?: DiffLine
  right?: DiffLine
  kind: 'same' | 'add' | 'remove'
}

/** 独立的提交前差异页面，默认展示导入基线与本地当前内容。 */
export default function GitHubDiffModal({ open, workspaceId, path, onClose }: GitHubDiffModalProps) {
  const [diff, setDiff] = useState<GitHubFileDiff>()
  const [loading, setLoading] = useState(false)
  const [maximized, setMaximized] = useState(false)
  const [side, setSide] = useState<'local' | 'remote'>('local')

  useEffect(() => {
    if (!open || !workspaceId || !path) return
    setLoading(true)
    setDiff(undefined)
    setSide('local')
    githubWorkspaceChangesApi.diff(workspaceId, path)
      .then(setDiff)
      .catch(error => message.error(getApiErrorMessage(error)))
      .finally(() => setLoading(false))
  }, [open, workspaceId, path])

  useEffect(() => {
    if (!open) setMaximized(false)
  }, [open])

  const afterContent = side === 'remote' && diff?.remoteContent != null ? diff.remoteContent : diff?.localContent
  const beforeContent = diff?.baseContent
  const computed = useMemo(() => {
    if (beforeContent == null || afterContent == null) return { rows: [] as DiffRow[], limited: false }
    return makeDiffRows(beforeContent, afterContent)
  }, [beforeContent, afterContent])

  const modalStyle = maximized ? { top: 0, maxWidth: '100vw', paddingBottom: 0 } : undefined
  const bodyStyle = maximized ? { height: 'calc(100vh - 110px)', padding: 0, overflow: 'hidden' } : { height: '70vh', padding: 0, overflow: 'hidden' }

  return <Modal
    className={`github-diff-modal${maximized ? ' is-maximized' : ''}`}
    open={open}
    width={maximized ? '100vw' : 1180}
    style={modalStyle}
    styles={{ body: bodyStyle }}
    title={<div className="github-diff-title"><span>提交前 Diff</span>{path && <Typography.Text code ellipsis={{ tooltip: path }}>{path}</Typography.Text>}<Button type="text" className="icon-button github-diff-maximize" aria-label={maximized ? '还原窗口' : '最大化窗口'} onClick={() => setMaximized(value => !value)} icon={maximized ? <Minimize2 size={17} /> : <Maximize2 size={17} />} /></div>}
    footer={null}
    onCancel={onClose}
    maskClosable={false}
  >
    {loading ? <div className="github-diff-loading"><Spin size="large" /></div> : !diff ? <Empty description="暂无 Diff 内容" /> : diff.binary || diff.truncated ? <BinaryDiff diff={diff} /> : <div className="github-diff-shell">
      {diff.remoteContent != null && diff.remoteSha !== diff.baseSha && <div className="github-diff-switcher"><Segmented value={side} onChange={value => setSide(value as 'local' | 'remote')} options={[{ label: '基线 → 本地', value: 'local' }, { label: '基线 → 远端', value: 'remote' }]} /><Tag className="source-tag">{side === 'local' ? '待提交内容' : '远端当前内容'}</Tag></div>}
      {computed.limited ? <div className="github-diff-limited"><Typography.Text type="secondary">文件行数较多，已切换为左右全文查看。</Typography.Text><SideBySideText left={beforeContent ?? ''} right={afterContent ?? ''} /></div> : <div className="github-diff-grid"><div className="github-diff-column-title">修改前 · 导入基线</div><div className="github-diff-column-title">修改后 · {side === 'local' ? '当前工作区' : '远端版本'}</div>{computed.rows.map((row, index) => <DiffRowView key={`${index}-${row.kind}`} row={row} />)}</div>}
    </div>}
  </Modal>
}

function BinaryDiff({ diff }: { diff: GitHubFileDiff }) {
  return <div className="github-diff-binary"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={diff.truncated ? '文件过大，暂不生成逐行 Diff' : '二进制文件不支持逐行 Diff'} /><Typography.Paragraph type="secondary">修改前：{shortSha(diff.baseSha)}　当前：{shortSha(diff.localSha)}　远端：{shortSha(diff.remoteSha)}</Typography.Paragraph></div>
}

function SideBySideText({ left, right }: { left: string; right: string }) {
  return <div className="github-diff-large-text"><pre>{left}</pre><pre>{right}</pre></div>
}

function DiffRowView({ row }: { row: DiffRow }) {
  return <><div className={`github-diff-cell ${row.kind === 'remove' ? 'is-remove' : ''}`}><span className="github-diff-line-number">{row.left?.number ?? ''}</span><code>{row.left?.value ?? ''}</code></div><div className={`github-diff-cell ${row.kind === 'add' ? 'is-add' : ''}`}><span className="github-diff-line-number">{row.right?.number ?? ''}</span><code>{row.right?.value ?? ''}</code></div></>
}

/** 使用带上限的 LCS 生成稳定的逐行左右 Diff，超大文件交给全文模式避免卡死浏览器。 */
function makeDiffRows(before: string, after: string): { rows: DiffRow[]; limited: boolean } {
  const left = before.split('\n')
  const right = after.split('\n')
  const limit = 1400
  if (left.length > limit || right.length > limit) return { rows: [], limited: true }
  const width = right.length + 1
  const matrix = new Uint32Array((left.length + 1) * width)
  for (let i = 1; i <= left.length; i += 1) {
    for (let j = 1; j <= right.length; j += 1) {
      matrix[i * width + j] = left[i - 1] === right[j - 1]
        ? matrix[(i - 1) * width + j - 1] + 1
        : Math.max(matrix[(i - 1) * width + j], matrix[i * width + j - 1])
    }
  }
  const rows: DiffRow[] = []
  let i = left.length
  let j = right.length
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && left[i - 1] === right[j - 1]) {
      rows.unshift({ kind: 'same', left: { number: i, value: left[i - 1] }, right: { number: j, value: right[j - 1] } })
      i -= 1; j -= 1
    } else if (i > 0 && (j === 0 || matrix[(i - 1) * width + j] >= matrix[i * width + j - 1])) {
      rows.unshift({ kind: 'remove', left: { number: i, value: left[i - 1] } })
      i -= 1
    } else {
      rows.unshift({ kind: 'add', right: { number: j, value: right[j - 1] } })
      j -= 1
    }
  }
  return { rows, limited: false }
}

function shortSha(value: string | null): string {
  return value ? value.slice(0, 8) : '无'
}
