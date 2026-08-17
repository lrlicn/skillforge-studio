import { Button, Divider, Tag, Tooltip, Typography } from 'antd'
import { FolderOpen, Github } from 'lucide-react'
import type { GitHubConnectionView } from '../api/client'

const { Title, Text, Paragraph } = Typography

export interface ConnectionsPageProps {
  connection?: GitHubConnectionView
  loading: boolean
  connecting: boolean
  disconnecting: boolean
  onChooseRepository: () => void
  onDisconnect: () => void
  onConnect: () => void
}

/** 外部连接页面明确区分未配置、未连接和已连接状态。 */
export default function ConnectionsPage(props: ConnectionsPageProps) {
  const connection = props.connection
  return <>
    <div className="page-heading"><div><Title level={2}>连接管理</Title><Paragraph type="secondary">管理平台访问外部代码托管服务的授权。</Paragraph></div></div>
    <section className="management-section connection-section" aria-label="外部连接">
      <div className="connection-main">
        <div className="connection-logo"><Github size={28} /></div>
        <div className="connection-copy">
          <Title level={4}>GitHub</Title>
          {props.loading && !connection
            ? <Text type="secondary">正在读取连接状态</Text>
            : connection?.connected
              ? <div className="connection-identity"><Text strong>{connection.displayName || connection.login}</Text><Text type="secondary">@{connection.login}</Text></div>
              : <Text type="secondary">{connection?.authorizationAvailable ? '尚未连接' : 'OAuth 尚未配置'}</Text>}
          <Paragraph>{connection?.connected ? '账号授权有效，可以继续选择仓库并获取 skills。' : '授权后可选择仓库、读取 skills，并在确认变更后提交和推送。'}</Paragraph>
        </div>
      </div>
      <div className="connection-actions">
        <Tag className={`source-tag${connection?.connected ? ' is-connected' : ''}`}>{connection?.connected ? '已连接' : '未连接'}</Tag>
        {connection?.connected && <Button icon={<FolderOpen size={16} />} onClick={props.onChooseRepository}>选择仓库</Button>}
        {connection?.connected && <Button loading={props.disconnecting} onClick={props.onDisconnect}>解除连接</Button>}
        <Tooltip title={!connection?.authorizationAvailable ? '请先在后端私密配置中启用 GitHub OAuth' : undefined}>
          <span><Button disabled={!connection?.authorizationAvailable} loading={props.connecting} type="primary" icon={<Github size={16} />} onClick={props.onConnect}>{connection?.connected ? '重新授权' : '连接 GitHub'}</Button></span>
        </Tooltip>
      </div>
      <Divider />
      <div className="permission-grid">
        <div><Text strong>{connection?.connected ? '已授权权限' : '计划申请权限'}</Text><Text type="secondary">{connection?.connected && connection.scopes.length > 0 ? connection.scopes.join(' · ') : '读取账号资料、访问用户明确选择的仓库'}</Text></div>
        <div><Text strong>授权原则</Text><Text type="secondary">未经用户授权不读取 GitHub 内容，可随时解除连接</Text></div>
      </div>
    </section>
  </>
}
