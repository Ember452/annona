import { Suspense } from 'react'
import { RouterProvider } from 'react-router-dom'
import { LoaderCircleIcon } from 'lucide-react'

import { router } from '@/router'

export default function App() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-background text-muted-foreground">
          <LoaderCircleIcon className="size-5 animate-spin text-primary" />
          <span className="text-sm">加载中…</span>
        </div>
      }
    >
      <RouterProvider router={router} />
    </Suspense>
  )
}
