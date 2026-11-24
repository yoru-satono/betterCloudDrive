"""Scoped headless Web/M1 acceptance for desktop interface changes.

Backend services must already be running. No credentials or browser traces are
written to reports; account audit contains IDs/names only.
"""
import argparse
import hashlib
import json
import base64
import uuid
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'tests')]
import httpx
from live_support import Api, wait_for, env_values, jwt, pdf_document
from m1_check import M1Validation
from m4_support import clean_environment, owned_root, remove_owned, require, process_fingerprints, surviving_owned
from process_metrics import sample_tree, sample_processes, terminate_owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--office", action="store_true", help="Include shared previews, real Office save/read-only and short Qt video")
    args = parser.parse_args()
    identity = secrets.token_hex(5)
    out = ROOT.parent / 'test-results/desktop' / identity; out.mkdir(parents=True)
    (out/'.m4-run').write_text(identity, encoding='utf-8'); owned_root(out)
    work = out/'work'; work.mkdir(); (work/'tmp').mkdir()
    pnpm=shutil.which('pnpm.cmd' if os.name=='nt' else 'pnpm')
    env=clean_environment(os.environ,[Path(pnpm).parent,Path(shutil.which('node')).parent])
    password=secrets.token_urlsafe(24)+'A1!'
    env.update(TEMP=str(work/'tmp'),TMP=str(work/'tmp'),TMPDIR=str(work/'tmp'),BCD_TEST_RUN_ID=identity,
               BCD_E2E_ACCOUNT_AUDIT=str(out/'account-audit.jsonl'),BCD_E2E_TEST_PASSWORD=password,
               E2E_BASE_URL='http://127.0.0.1:5197',E2E_BACKEND_URL='http://127.0.0.1:8080',E2E_MAILPIT_URL='http://127.0.0.1:8025')
    for key in list(env):
        if key.startswith('PLAYWRIGHT_JSON_OUTPUT'): del env[key]
    processes, owned, checks, mail_addresses = [], {}, [], set()
    if args.office:
        values = env_values(ROOT.parent)
        env.update(E2E_ONLYOFFICE='1', ONLYOFFICE_JWT_SECRET=values['ONLYOFFICE_JWT_SECRET'],
                   JWT_ACCESS_SECRET=values['JWT_ACCESS_SECRET'], BCD_TEST_PYTHON=sys.executable,
                   BCD_SHARED_FIXTURES=str(work), BCD_RENDERING_MODULE=str(ROOT/'tests'))
    failed=False
    def collect():
        for process in processes:
            if process.poll() is None: owned.update(process_fingerprints(sample_tree(process.pid)))
    def run_native(validation):
        stop = threading.Event(); native_owned = {}
        def sample():
            while not stop.is_set():
                process = validation.process
                if process is not None and process.poll() is None:
                    values = process_fingerprints(sample_tree(process.pid))
                    owned.update(values); native_owned.update(values)
                stop.wait(.2)
        sampler = threading.Thread(target=sample, daemon=True); sampler.start()
        try: return validation.run()
        finally:
            stop.set(); sampler.join(timeout=5)
            deadline = time.monotonic()+5
            while surviving_owned(native_owned, sample_processes(native_owned)) and time.monotonic()<deadline: time.sleep(.1)
            remaining = surviving_owned(native_owned, sample_processes(native_owned))
            if remaining: terminate_owned({pid:native_owned[pid] for pid in remaining})
            require(not remaining, 'NATIVE_CHILD_PROCESS_RETAINED')
    try:
        with socket.socket() as probe: probe.bind(('127.0.0.1',5197))
        frontend=ROOT.parent/'frontend'
        build=subprocess.run([sys.executable,str(ROOT/'build.py'),'--out-dir',str(work/'web')],env=env,cwd=ROOT,
                             stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
        require(build.returncode==0,'SHARED_BUILD_FAILED')
        def fingerprint():
            digest=hashlib.sha256()
            for path in sorted((work/'web').rglob('*')):
                if path.is_file(): digest.update(str(path.relative_to(work/'web')).encode()); digest.update(path.read_bytes())
            return digest.hexdigest()
        shared_digest=fingerprint()
        if args.office:
            (work/'preview.pdf').write_bytes(pdf_document())
            import imageio_ffmpeg
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-y','-f','lavfi','-i','testsrc2=size=640x360:rate=30',
                            '-t','3','-threads','2','-c:v','libvpx-vp9','-deadline','realtime','-cpu-used','8',str(work/'preview.webm')],
                            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
        vite=subprocess.Popen([pnpm,'exec','vite','preview','--outDir',str(work/'web'),'--host','127.0.0.1','--port','5197','--strictPort'],cwd=frontend,env=env,
                              stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        processes.append(vite)
        def ready():
            collect(); require(vite.poll() is None,'VITE_EXITED')
            try: return httpx.get(env['E2E_BASE_URL'],trust_env=False).status_code==200
            except httpx.HTTPError: return False
        wait_for(ready,30)
        config=work/'cutover.config.mts'
        config.write_text('import base from '+json.dumps((frontend/'playwright.config.ts').as_uri())+';\nexport default {...base,'+
            'testDir:'+json.dumps(str(frontend/'e2e'))+',outputDir:'+json.dumps(str(work/'artifacts'))+','+
            'webServer:undefined,reporter:[["json"]],retries:0,projects:[{name:"cutover",testMatch:'+json.dumps([
                '**/auth.e2e.ts','**/files.e2e.ts','**/download-resume.e2e.ts','**/theme.e2e.ts',
                *(['**/shared-preview.acceptance.ts','**/onlyoffice-edit-save.e2e.ts','**/onlyoffice-personal-preview.e2e.ts'] if args.office else [])])+'}],grepInvert:/saves team (xlsx|pptx)/,'+
            'use:{...base.use,baseURL:'+json.dumps(env['E2E_BASE_URL'])+',headless:true,channel:"chromium",trace:"off",video:"off",screenshot:"off"}};\n',encoding='utf-8')
        runner=subprocess.Popen([pnpm,'exec','playwright','test','--config',str(config)],cwd=frontend,env=env,
                                stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace')
        processes.append(runner); end=time.monotonic()+600
        while True:
            collect()
            try: output,_=runner.communicate(timeout=.2); break
            except subprocess.TimeoutExpired: require(time.monotonic()<end,'WEB_E2E_TIMEOUT')
        # The raw JSON can contain assertion call arguments. Keep it in memory
        # only, publish titles/statuses, and discard errors, traces and tokens.
        start=output.find('{\n  "config"')
        require(start>=0,'PLAYWRIGHT_REPORT_MISSING')
        data=json.JSONDecoder().raw_decode(output[start:])[0]
        cases=[]
        def walk(suite):
            for spec in suite.get('specs',[]):
                for test in spec['tests']:
                    result=test['results'][-1] if test['results'] else {}
                    locations=[{'file':Path(e['location']['file']).name,'line':e['location']['line']}
                               for e in result.get('errors',[]) if e.get('location')]
                    cases.append({'title':spec['title'],'status':result.get('status','not-executed'),'failureLocations':locations, 'diagnostics': [a.get('description') for a in test.get('annotations', []) if a.get('type')=='shared-preview-media']})
            for child in suite.get('suites',[]): walk(child)
        for suite in data['suites']: walk(suite)
        checks.append({'name':'web-playwright','status':'passed' if runner.returncode==0 else 'failed','cases':cases})
        require(runner.returncode==0 and len(cases)>=5 and all(c['status']=='passed' for c in cases),'WEB_E2E_FAILED')
        print('Scoped Web Playwright passed',len(cases),flush=True)
        m1=M1Validation('http://127.0.0.1:8080/api/v1',False,port=5194,debug_port=9226,offscreen=True)
        m1_report=m1.out; (m1_report/'.m4-run').write_text(m1.id,encoding='utf-8')
        m1.out=work/'m1-runtime'; m1.out.mkdir()
        m1.web_root=work/'web'; m1.process_env=env
        code=run_native(m1)
        (m1.out/'results.json').replace(m1_report/'results.json')
        mail_addresses.update(user['username']+'@test.local' for user in m1.api.users)
        checks.append({'name':'real-qt-m1','status':'passed' if code==0 else 'failed','reportId':m1.id})
        require(code==0,'QT_M1_FAILED')
        if args.office:
            from m3_check import M3
            m3=M3('http://127.0.0.1:8080/api/v1','shared')
            report_root=m3.out
            m3.out=work/'m3-runtime'; m3.out.mkdir()
            m3.web_root=work/'web'; m3.process_env=env
            code=run_native(m3)
            (m3.out/'results.json').replace(report_root/'results.json')
            mail_addresses.update(user['username']+'@test.local' for user in m3.api.users)
            checks.append({'name':'shared-qt-previews-office-video','status':'passed' if code==0 else 'failed','reportId':m3.id})
            require(code==0,'SHARED_QT_PREVIEW_FAILED')
        require(fingerprint()==shared_digest,'SHARED_ARTIFACT_CHANGED')
        checks.append({'name':'identical-web-qt-artifact','status':'passed','sha256':shared_digest})
    except (Exception,KeyboardInterrupt) as error:
        failed=True
        code=str(error) if str(error).isupper() and len(str(error))<80 else type(error).__name__
        checks.append({'name':'execution','status':'failed','code':code})
    finally:
        collect(); terminate_owned(owned)
        for process in processes:
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: failed=True
        remaining=surviving_owned(owned,sample_processes(owned))
        checks.append({'name':'owned-process-release','status':'failed' if remaining else 'passed'})
        failed |= bool(remaining)
        api=Api(identity)
        try:
            audit=out/'account-audit.jsonl'
            records=[json.loads(line) for line in audit.read_text().splitlines()] if audit.exists() else []
            for account in {r['username']:r for r in records}.values():
                require(account['username'].startswith('e2e_') and '_'+identity+'_' in account['username'],'WEB_CLEANUP_SCOPE_DENIED')
                key=base64.b64decode(env_values(ROOT.parent)['JWT_ACCESS_SECRET'])
                token=jwt({'sub':str(account['userId']),'username':account['username'],'role':'ROLE_USER',
                           'jti':uuid.uuid4().hex,'iat':int(time.time()),'exp':int(time.time())+300},key)
                user={'accessToken':token}
                me=api.call('GET','/auth/me',user)
                require(str(me['id'])==str(account['userId']) and me['username']==account['username'],'WEB_CLEANUP_ACCOUNT_MISMATCH')
                roots=api.call('GET','/files',user,params={'page':1,'size':100})['records']
                if roots: api.call('DELETE','/files',user,json={'fileIds':[item['id'] for item in roots]})
                trash=api.call('GET','/recycle-bin',user,params={'page':1,'size':100})['records']
                for item in trash: api.call('DELETE',f"/recycle-bin/{item['id']}",user)
                teams=api.call('GET','/teams',user,params={'page':1,'size':100})['records']
                for team in teams:
                    if str(team['ownerId'])==str(account['userId']):
                        require(identity in team['name'],'TEAM_CLEANUP_SCOPE_DENIED')
                        api.call('DELETE',f"/teams/{team['id']}",user)
                api.call('POST','/auth/logout',user)
                mail_addresses.add(account['email'])
            checks.append({'name':'run-scoped-web-data-cleanup','status':'passed','accountAuditCount':len(records)})
        except Exception as error:
            failed=True; checks.append({'name':'run-scoped-web-data-cleanup','status':'failed','code':type(error).__name__})
        finally: api.client.close()
        try:
            response=httpx.get('http://127.0.0.1:8025/api/v1/messages',trust_env=False); response.raise_for_status()
            ids=[m['ID'] for m in response.json().get('messages',[]) if any(t.get('Address') in mail_addresses for t in m.get('To',[]))]
            if ids:
                response=httpx.request('DELETE','http://127.0.0.1:8025/api/v1/messages',json={'IDs':ids},trust_env=False); response.raise_for_status()
            checks.append({'name':'run-mail-cleanup','status':'passed','messages':len(ids)})
        except Exception as error:
            failed=True; checks.append({'name':'run-mail-cleanup','status':'failed','code':type(error).__name__})
        try: remove_owned(out,work); checks.append({'name':'owned-work-cleanup','status':'passed'})
        except Exception as error:
            failed=True; checks.append({'name':'owned-work-cleanup','status':'failed','code':type(error).__name__})
        report={'runId':identity,'status':'failed' if failed else 'passed','checks':checks}
        (out/'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('Cutover report:',out/'results.json',flush=True)
    return int(failed)


if __name__=='__main__': sys.exit(main())
