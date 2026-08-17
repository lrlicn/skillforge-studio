import { loader } from '@monaco-editor/react'
import Editor from '@monaco-editor/react'
import * as monaco from 'monaco-editor/editor/editor.api'
import editorWorker from 'monaco-editor/editor/editor.worker?worker'
import { useEffect, useRef } from 'react'

/**
 * 当前阶段提供可靠的文本编辑能力，因此不加载额外语言服务和诊断 Worker。
 * 编辑器 Worker 由 Vite 打包到本地，运行时不请求公共 CDN。
 */
self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker()
  }
}
loader.config({ monaco })

/**
 * 编辑器使用固定浅色主题，显式指定文本光标颜色。
 * 这可以避免操作系统或浏览器主题切换后，光标在白色编辑区中变成难以辨认的浅色。
 */
monaco.editor.defineTheme('skillforge-light', {
  base: 'vs',
  inherit: true,
  rules: [],
  colors: {
    'editor.background': '#FFFFFF',
    'editor.foreground': '#262626',
    'editorCursor.foreground': '#262626'
  }
})

export interface LocalEditorProps {
  value: string
  language: string
  readOnly: boolean
  onChange?: (value: string) => void
  onCursorLineChange?: (lineNumber: number) => void
  onScrollRatioChange?: (ratio: number) => void
  scrollRatio?: number
}

/** 本地源码编辑器；向外暴露光标行和滚动比例，供 Markdown 分屏预览建立联动。 */
export default function LocalEditor({
  value,
  language,
  readOnly,
  onChange,
  onCursorLineChange,
  onScrollRatioChange,
  scrollRatio
}: LocalEditorProps) {
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const suppressChangeRef = useRef(false)
  const onChangeRef = useRef(onChange)
  const onCursorLineChangeRef = useRef(onCursorLineChange)
  const onScrollRatioChangeRef = useRef(onScrollRatioChange)

  useEffect(() => { onChangeRef.current = onChange }, [onChange])
  useEffect(() => { onCursorLineChangeRef.current = onCursorLineChange }, [onCursorLineChange])
  useEffect(() => { onScrollRatioChangeRef.current = onScrollRatioChange }, [onScrollRatioChange])

  /**
   * Monaco 自己维护键盘输入，父组件只在切换文件、取消编辑等外部场景同步不同内容。
   * 普通输入时模型内容与 value 已相同，因此不会调用 setValue，也不会打断中文输入法或移动光标。
   */
  useEffect(() => {
    const editor = editorRef.current
    const model = editor?.getModel()
    if (!editor || !model || model.getValue() === value) return

    const viewState = editor.saveViewState()
    suppressChangeRef.current = true
    model.setValue(value)
    suppressChangeRef.current = false
    if (viewState) editor.restoreViewState(viewState)
  }, [value])

  useEffect(() => {
    if (scrollRatio == null || !editorRef.current) return
    const editor = editorRef.current
    const maxScrollTop = Math.max(0, editor.getScrollHeight() - editor.getLayoutInfo().height)
    editor.setScrollTop(maxScrollTop * Math.min(1, Math.max(0, scrollRatio)))
  }, [scrollRatio])

  return <Editor
    height="100%"
    language={language}
    theme="skillforge-light"
    defaultValue={value}
    onChange={nextValue => {
      if (!suppressChangeRef.current) onChangeRef.current?.(nextValue ?? '')
    }}
    onMount={editor => {
      editorRef.current = editor
      editor.onDidChangeCursorPosition(event => onCursorLineChangeRef.current?.(event.position.lineNumber))
      editor.onDidScrollChange(() => {
        const maxScrollTop = Math.max(0, editor.getScrollHeight() - editor.getLayoutInfo().height)
        onScrollRatioChangeRef.current?.(maxScrollTop === 0 ? 0 : editor.getScrollTop() / maxScrollTop)
      })
    }}
    options={{
      readOnly,
      minimap: { enabled: false },
      fontSize: 14,
      // 横向空间由编辑画布统一管理，源码在固定宽度内自动换行，避免出现第二条横向滚动条。
      wordWrap: 'on',
      padding: { top: 18 },
      scrollBeyondLastLine: false,
      mouseStyle: 'text',
      cursorStyle: 'line',
      cursorBlinking: 'smooth',
      scrollbar: {
        vertical: 'hidden',
        horizontal: 'hidden',
        verticalScrollbarSize: 0,
        horizontalScrollbarSize: 0,
        alwaysConsumeMouseWheel: false
      }
    }}
  />
}
