/** GitHub 工作区单个受跟踪文件的变更状态。 */
export type GitHubFileChangeStatus = 'LOCAL_MODIFIED' | 'REMOTE_MODIFIED' | 'REMOTE_DELETED' | 'CONFLICT'

/** 后端返回的单文件比较结果。 */
export interface GitHubWorkspaceFileChange {
  path: string
  status: GitHubFileChangeStatus
  localChanged: boolean
  remoteChanged: boolean
  localSha256: string
  baseBlobSha: string | null
  remoteBlobSha: string | null
}

/** GitHub 工作区与当前远端分支的比较摘要。 */
export interface GitHubWorkspaceChanges {
  workspaceId: string
  repositoryFullName: string
  repositoryRef: string
  baseCommitSha: string | null
  remoteCommitSha: string
  baselineAvailable: boolean
  remoteAdvanced: boolean
  trackedFileCount: number
  localChangeCount: number
  remoteChangeCount: number
  conflictCount: number
  changes: GitHubWorkspaceFileChange[]
}
