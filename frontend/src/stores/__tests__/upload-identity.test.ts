import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia, disposePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { toast } from 'vue-sonner'
import { useUploadStore } from '@/stores/upload'
import { invalidateIdentity } from '@/platform/identity'
import { createResumableScope, saveResumableUpload, readResumableUploads } from '@/api/resumableUpload'
import * as uploadApi from '@/api/upload'

vi.mock('@/api/upload',()=>({initUpload:vi.fn(),uploadChunk:vi.fn(),completeMultipart:vi.fn(),abortMultipart:vi.fn(),getUploadStatus:vi.fn()}))
const deferred=()=>{let resolve!:(v:any)=>void,reject!:(v:any)=>void;const promise=new Promise<any>((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const scope=createResumableScope('/api/v1',7)
const record=()=>({sessionId:'private-a',parentId:null,fileName:'A-private.txt',displayName:'A-private.txt',fileSize:8,md5Hash:'a',totalChunks:1,chunkSize:8,createdAt:Date.now(),updatedAt:Date.now()})
let pinia:ReturnType<typeof createPinia>
beforeEach(()=>{
  invalidateIdentity('test');pinia=createPinia();setActivePinia(pinia);localStorage.clear()
  Object.values(uploadApi).forEach(fn=>(fn as ReturnType<typeof vi.fn>).mockReset())
  vi.mocked(uploadApi.getUploadStatus).mockResolvedValue({data:{data:{uploadedChunks:0,totalChunks:1}}} as never)
})
afterEach(()=>{disposePinia(pinia);vi.unstubAllGlobals()})
const authenticate=(id=7)=>{localStorage.setItem('accessToken','synthetic-'+id);useUploadStore().activateIdentity('/api/v1',id)}

it('does not read or query a stored task before authentication; restores it once after activation',async()=>{
  saveResumableUpload(scope,record())
  const store=useUploadStore();store.restoreResumableUploads()
  expect(store.queue).toEqual([]);expect(uploadApi.getUploadStatus).not.toHaveBeenCalled()
  authenticate();authenticate();await flushPromises()
  expect(store.queue).toHaveLength(1);expect(store.queue[0].status).toBe('resume_required')
  expect(uploadApi.getUploadStatus).toHaveBeenCalledTimes(1);expect(uploadApi.initUpload).not.toHaveBeenCalled()
  invalidateIdentity('logout')
  expect(store.queue).toEqual([]);expect(store.isOpen).toBe(false)
  expect(readResumableUploads(scope)).toHaveLength(1)
  authenticate(8);expect(store.queue).toEqual([])
  invalidateIdentity('login');authenticate();await flushPromises()
  expect(store.queue[0]?.fileName).toBe('A-private.txt')
})

it.each(['success','failure'])('discards a late status %s without deleting the old account cache',async outcome=>{
  saveResumableUpload(scope,record());const status=deferred()
  vi.mocked(uploadApi.getUploadStatus).mockReturnValueOnce(status.promise)
  authenticate();invalidateIdentity('connection');authenticate(8)
  if(outcome==='success')status.resolve({data:{data:{uploadedChunks:1,totalChunks:1}}})
  else status.reject({response:{data:{code:404001}}})
  await flushPromises()
  expect(useUploadStore().queue).toEqual([]);expect(readResumableUploads(scope)).toHaveLength(1)
  expect(toast.error).not.toHaveBeenCalled()
})

it.each([403001,'network',404001,419006])('only removes confirmed unavailable sessions, not failure %s',async code=>{
  saveResumableUpload(scope,record())
  vi.mocked(uploadApi.getUploadStatus).mockRejectedValueOnce(code==='network'?new Error('offline'):{response:{data:{code}}})
  authenticate();await flushPromises()
  const expired=code===404001||code===419006
  expect(readResumableUploads(scope)).toHaveLength(expired?0:1)
  expect(useUploadStore().queue[0]).toMatchObject({status:'error',resumable:!expired})
})

it('does not initialize an old file after hashing finishes under a new identity',async()=>{
  let complete!:()=>void
  vi.stubGlobal('FileReader',class {onload:any;readAsArrayBuffer(){complete=()=>this.onload({target:{result:new ArrayBuffer(8)}})}})
  authenticate();useUploadStore().addFiles([new File(['abcdefgh'],'old.txt')],null)
  invalidateIdentity('login');authenticate(8);complete();await flushPromises()
  expect(uploadApi.initUpload).not.toHaveBeenCalled();expect(useUploadStore().queue).toEqual([])
})

it('does not cancel or persist an old initialization result using the next identity',async()=>{
  const init=deferred();vi.mocked(uploadApi.initUpload).mockReturnValueOnce(init.promise)
  vi.stubGlobal('FileReader',class {onload:any;readAsArrayBuffer(){queueMicrotask(()=>this.onload({target:{result:new ArrayBuffer(8)}}))}})
  authenticate();useUploadStore().addFiles([new File(['abcdefgh'],'old.txt')],null);await flushPromises()
  expect(uploadApi.initUpload).toHaveBeenCalledTimes(1)
  invalidateIdentity('login');authenticate(8)
  init.resolve({data:{data:{mode:'MULTIPART',sessionId:'late',totalChunks:1,chunkSize:8}}});await flushPromises()
  expect(uploadApi.abortMultipart).not.toHaveBeenCalled();expect(uploadApi.uploadChunk).not.toHaveBeenCalled()
  expect(readResumableUploads(scope)).toEqual([]);expect(toast.error).not.toHaveBeenCalled()
})

it('discards an old cancellation result after switching account',async()=>{
  saveResumableUpload(scope,record());authenticate();await flushPromises()
  const cancellation=deferred();vi.mocked(uploadApi.abortMultipart).mockReturnValueOnce(cancellation.promise)
  const store=useUploadStore(),canceling=store.cancelUpload(store.queue[0].id)
  invalidateIdentity('login');authenticate(8);cancellation.resolve({});await canceling
  expect(store.queue).toEqual([]);expect(readResumableUploads(scope)).toHaveLength(1)
  expect(uploadApi.abortMultipart).toHaveBeenCalledTimes(1)
})
