import { Suspense } from 'react'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'

export default function App() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center text-neutral-500">
          加载中…
        </div>
      }
    >
      <RouterProvider router={router} />
    </Suspense>
  )
}
