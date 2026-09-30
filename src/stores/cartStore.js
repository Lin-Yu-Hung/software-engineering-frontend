import { reactive } from 'vue'

export const cartStore = reactive({
  items: [],
})

export function addToCart(item) {
  const exists = cartStore.items.some(
    (i) => i.courtId === item.courtId && i.slot === item.slot && i.date === item.date
  )
  if (!exists) cartStore.items.push(item)
}

export function removeFromCart(courtId, slot, date) {
  const idx = cartStore.items.findIndex(
    (i) => i.courtId === courtId && i.slot === slot && i.date === date
  )
  if (idx !== -1) cartStore.items.splice(idx, 1)
}

export function clearCart() {
  cartStore.items.splice(0, cartStore.items.length)
}
