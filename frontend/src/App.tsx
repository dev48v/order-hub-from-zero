import { useEffect, useState } from 'react'
import type { Order, CreateOrderRequest } from './types'
import { api, USING_MOCK } from './api'
import { OrderForm } from './components/OrderForm'
import { OrderList } from './components/OrderList'

export default function App() {
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [confirmingId, setConfirmingId] = useState<string | null>(null)
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null)

  function flash(msg: string, ok = true) {
    setToast({ msg, ok }); setTimeout(() => setToast(null), 2600)
  }

  // load orders on mount
  useEffect(() => {
    api.listOrders().then(setOrders).catch((e) => flash(e.message, false)).finally(() => setLoading(false))
  }, [])

  async function placeOrder(req: CreateOrderRequest) {
    setBusy(true)
    try {
      await api.placeOrder(req)
      setOrders(await api.listOrders())
      flash('Order placed')
    } catch (e) { flash((e as Error).message, false) } finally { setBusy(false) }
  }

  async function confirmOrder(id: string) {
    setConfirmingId(id)
    try {
      await api.confirmOrder(id)
      setOrders(await api.listOrders())
      flash('Order confirmed')
    } catch (e) { flash((e as Error).message, false) } finally { setConfirmingId(null) }
  }

  return (
    <div className="min-h-screen">
      {/* header */}
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-600 text-lg">📦</div>
            <div>
              <div className="text-base font-bold text-slate-900">OrderHub</div>
              <div className="text-xs text-slate-400">Production-grade Spring Boot, day by day</div>
            </div>
          </div>
          <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${USING_MOCK ? 'bg-amber-100 text-amber-700' : 'bg-brand-50 text-brand-700'}`}>
            {USING_MOCK ? '● demo (mock data)' : '● live API'}
          </span>
        </div>
      </header>

      {/* body */}
      <main className="mx-auto grid max-w-5xl gap-6 px-5 py-8 md:grid-cols-[320px_1fr]">
        <OrderForm onPlace={placeOrder} busy={busy} />
        <OrderList orders={orders} loading={loading} onConfirm={confirmOrder} confirmingId={confirmingId} />
      </main>

      {/* toast */}
      {toast && (
        <div className={`fixed bottom-5 left-1/2 -translate-x-1/2 rounded-lg px-4 py-2 text-sm font-medium text-white shadow-lg ${toast.ok ? 'bg-slate-900' : 'bg-rose-600'}`}>
          {toast.msg}
        </div>
      )}

      <footer className="py-8 text-center text-xs text-slate-400">
        Day 1 · Order REST API + layered architecture ·{' '}
        <a className="font-medium text-brand-600 hover:underline" href="https://github.com/dev48v/order-hub-from-zero" target="_blank" rel="noreferrer">github.com/dev48v/order-hub-from-zero</a>
      </footer>
    </div>
  )
}
