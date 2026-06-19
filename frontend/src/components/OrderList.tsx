import type { Order } from '../types'
import { StatusBadge } from './StatusBadge'

const fmt = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })

export function OrderList({
  orders, loading, onConfirm, confirmingId,
}: {
  orders: Order[]
  loading: boolean
  onConfirm: (id: string) => void
  confirmingId: string | null
}) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3">
        <h2 className="text-sm font-semibold text-slate-900">Orders</h2>
        <span className="text-xs text-slate-400">{orders.length} total</span>
      </div>

      {loading ? (
        <div className="p-10 text-center text-sm text-slate-400">Loading orders…</div>
      ) : orders.length === 0 ? (
        <div className="p-10 text-center text-sm text-slate-400">No orders yet — place one on the left.</div>
      ) : (
        <ul className="divide-y divide-slate-100">
          {orders.map((o) => (
            <li key={o.id} className="flex items-center gap-4 px-5 py-3.5">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-slate-100 text-sm font-semibold text-slate-600">
                {o.customer.charAt(0).toUpperCase()}
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium text-slate-900">{o.item} <span className="text-slate-400">×{o.quantity}</span></div>
                <div className="text-xs text-slate-400">{o.customer} · {fmt(o.createdAt)}</div>
              </div>
              <StatusBadge status={o.status} />
              {o.status === 'PLACED' && (
                <button
                  onClick={() => onConfirm(o.id)}
                  disabled={confirmingId === o.id}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-brand-500 hover:text-brand-700 disabled:opacity-50"
                >
                  {confirmingId === o.id ? '…' : 'Confirm'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
