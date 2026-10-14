import { showToast } from 'vant'
import { getToken } from '../../api'

export async function uploadWarehousePhoto(file:any):Promise<{id:number;name:string}>{
  const form=new FormData();form.append('file',file.file)
  const headers:Record<string,string>={};const token=getToken();if(token)headers.Authorization=`Bearer ${token}`
  const response=await fetch('/api/mobile/attachments',{method:'POST',headers,body:form});const body=await response.json()
  if(!response.ok||!body.success)throw new Error(body.message||'照片上传失败')
  return body.data
}
export function onPhotoError(error:unknown){showToast(error instanceof Error?error.message:'照片上传失败')}
