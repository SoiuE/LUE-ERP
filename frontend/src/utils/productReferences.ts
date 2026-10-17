import { ref } from 'vue'
import { api } from '../api'

export type ProductReference = { id:number; sourceType:string; externalUrl:string }
export type ProductObjectType = 'SKU' | 'WAREHOUSE_GOODS'

export function useProductReferences(type:ProductObjectType) {
  const images = ref<Record<string,ProductReference>>({})
  let generation = 0
  async function load(rawIds:Array<number|null|undefined>, merge=false) {
    const current = ++generation
    const ids = [...new Set(rawIds.filter((id):id is number=>Number.isSafeInteger(id) && Number(id)>0))]
    const previous = merge ? images.value : {}
    if (!merge) images.value = {}
    if (!ids.length) return
    try {
      const pages = await Promise.all(Array.from({length:Math.ceil(ids.length/100)},(_,index)=>{
        const chunk = ids.slice(index*100,(index+1)*100)
        return api<{items:Record<string,ProductReference>}>(`/api/images/references?objectType=${type}&ids=${chunk.join(',')}`)
      }))
      if (current===generation) images.value = Object.assign({},previous,...pages.map(page=>page.items))
    } catch {
      if (current===generation) images.value = previous
    }
  }
  function get(id:number|null|undefined) { return id ? images.value[String(id)] : undefined }
  return { images, load, get }
}
