import type { OrderStatus } from '../types'

// A shadcn-style status pill — colour-coded by order state.
const STYLES: Record<OrderStatus, string> = {
  PLACED: 'bg-amber-100 text-amber-700 ring-amber-200',
  CONFIRMED: 'bg-brand-50 text-brand-700 ring-green-200',
  SHIPPED: 'bg-sky-100 text-sky-700 ring-sky-200',
  CANCELLED: 'bg-rose-100 text-rose-700 ring-rose-200',
}

export function StatusBadge({ status }: { status: OrderStatus }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${STYLES[status]}`}>
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  )
}
