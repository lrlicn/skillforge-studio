import React from 'react'
import ReactDOM from 'react-dom/client'
import '@ant-design/v5-patch-for-react-19'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import './styles/app.css'

/**
 * 前端唯一挂载入口。全局主题在此集中配置，保证业务组件不会自行引入宝蓝、白、灰以外的颜色。
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        // 基础 Token 约束全站颜色和字体；卡片保持克制圆角，交互控件单独使用胶囊样式。
        token: {
          colorPrimary: '#4B5CC4',
          colorPrimaryHover: '#4B5CC4',
          colorPrimaryActive: '#4B5CC4',
          colorInfo: '#4B5CC4',
          colorSuccess: '#4B5CC4',
          colorError: '#595959',
          colorWarning: '#595959',
          colorText: '#262626',
          colorTextSecondary: '#595959',
          colorBorder: '#E5E5E5',
          colorBgContainer: '#FFFFFF',
          borderRadius: 8,
          fontFamily: '"Microsoft YaHei", "PingFang SC", sans-serif'
        },
        components: {
          Button: { borderRadius: 999 },
          Input: { borderRadius: 999, activeShadow: '0 0 0 2px rgba(75, 92, 196, 0.12)' },
          Menu: { itemSelectedBg: '#F5F5F5', itemSelectedColor: '#4B5CC4', itemHoverBg: '#F5F5F5' },
          Select: { borderRadius: 999 },
          Segmented: { borderRadius: 999 },
          Tree: { nodeHoverBg: '#F5F5F5', nodeSelectedBg: '#F5F5F5' }
        }
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>
)
