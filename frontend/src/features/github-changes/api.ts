import { httpClient } from '../../api/client'
import type { GitHubWorkspaceChanges } from './types'

/** GitHub 工作区比较使用独立 API 模块，后续提交与同步接口继续在此扩展。 */
export const githubWorkspaceChangesApi = {
  compare: (workspaceId: string) => httpClient
    .get<GitHubWorkspaceChanges>(`/github/workspaces/${workspaceId}/changes`, { timeout: 120000 })
    .then(response => response.data)
  ,
  commit: (workspaceId: string, message: string) => httpClient
    .post<GitHubCommitResult>(`/github/workspaces/${workspaceId}/commits`, { message }, { timeout: 120000 })
    .then(response => response.data)
  ,
  diff: (workspaceId: string, path: string) => httpClient
    .get<GitHubFileDiff>(`/github/workspaces/${workspaceId}/changes/diff`, { params: { path }, timeout: 120000 })
    .then(response => response.data)
}

/** GitHub 提交成功后的追踪信息。 */
export interface GitHubCommitResult {
  workspaceId: string
  repositoryFullName: string
  repositoryRef: string
  commitSha: string
  commitUrl: string | null
  paths: string[]
  changedFileCount: number
}

/** 提交前单文件差异内容。 */
export interface GitHubFileDiff {
  path: string
  status: string
  binary: boolean
  truncated: boolean
  baseContent: string | null
  localContent: string | null
  remoteContent: string | null
  baseSha: string | null
  localSha: string | null
  remoteSha: string | null
}
