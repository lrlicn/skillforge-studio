import { Avatar, Button, Divider, Space, Typography } from 'antd'
import { Link2, LogOut } from 'lucide-react'
import type { CurrentUser } from '../api/client'

const { Title, Text, Paragraph } = Typography

export interface ProfilePageProps {
  user: CurrentUser
  onOpenConnections: () => void
  onLogout: () => void
}

/** 账号页展示当前平台身份和外部连接入口。 */
export default function ProfilePage({ user, onOpenConnections, onLogout }: ProfilePageProps) {
  return <>
    <div className="page-heading"><div><Title level={2}>账号资料</Title><Paragraph type="secondary">查看当前登录账号和关联入口。</Paragraph></div></div>
    <section className="management-section profile-section" aria-label="账号资料">
      <div className="profile-identity"><Avatar size={64} className="avatar">{user.username.slice(0, 1).toUpperCase()}</Avatar><div><Title level={3}>{user.username}</Title><Text type="secondary">平台本地账号</Text></div></div>
      <Divider />
      <div className="profile-fields"><div><Text type="secondary">用户名</Text><Text strong>{user.username}</Text></div><div><Text type="secondary">邮箱</Text><Text strong>{user.email}</Text></div><div><Text type="secondary">用户编号</Text><Text strong>{user.id}</Text></div></div>
      <Divider />
      <Space><Button icon={<Link2 size={16} />} onClick={onOpenConnections}>管理外部连接</Button><Button icon={<LogOut size={16} />} onClick={onLogout}>退出登录</Button></Space>
    </section>
  </>
}
