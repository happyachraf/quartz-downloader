"""Publish an already built and verified Quartz Downloader release to GitHub.

Credentials are read from GH_TOKEN/GITHUB_TOKEN or git's credential manager and
never written to a file. Run with --help for the staged publication steps.
"""
import argparse,hashlib,http.client,json,os,re,subprocess,sys,urllib.request,urllib.error,urllib.parse
from pathlib import Path
OWNER='happyachraf';REPO='quartz-downloader'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def token():
    value=os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if value:return value
    env=os.environ.copy();env.update(GIT_TERMINAL_PROMPT='0',GCM_INTERACTIVE='Never')
    output=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',capture_output=True,text=True,env=env,timeout=30)
    data=dict(line.split('=',1) for line in output.stdout.splitlines() if '=' in line)
    if output.returncode or not data.get('password'):raise RuntimeError('No GitHub credential is available. Authenticate Git for github.com first.')
    return data['password']
def api(path,method='GET',body=None):
    req=urllib.request.Request('https://api.github.com'+path,method=method,headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json','User-Agent':'Quartz-Downloader-Release','X-GitHub-Api-Version':'2022-11-28'},data=json.dumps(body).encode() if body is not None else None)
    if body is not None:req.add_header('Content-Type','application/json')
    try:
        with urllib.request.urlopen(req,timeout=60) as response:return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code==404:return None
        raise RuntimeError('GitHub API '+str(error.code)+': '+error.read(1500).decode(errors='replace')) from None
def upload(release,path):
    existing=next((a for a in release['assets'] if a['name']==path.name),None)
    if existing:
        if existing.get('digest')=='sha256:'+sha(path) and existing['size']==path.stat().st_size:return existing
        raise RuntimeError('Release already contains a different asset named '+path.name)
    url=urllib.parse.urlparse(release['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(path.name))
    if url.hostname!='uploads.github.com':raise RuntimeError('Unexpected GitHub upload host')
    connection=http.client.HTTPSConnection(url.hostname,timeout=180)
    try:
        connection.putrequest('POST',url.path+'?'+url.query)
        for k,v in {'Authorization':'Bearer '+TOKEN,'Content-Type':'application/octet-stream','Content-Length':str(path.stat().st_size),'User-Agent':'Quartz-Downloader-Release','Accept':'application/vnd.github+json'}.items():connection.putheader(k,v)
        connection.endheaders()
        with path.open('rb') as source:
            while True:
                chunk=source.read(1024*1024)
                if not chunk:break
                connection.send(chunk)
        response=connection.getresponse();data=json.loads(response.read())
        if response.status!=201:raise RuntimeError('Asset upload failed: '+str(response.status))
        if data.get('digest')!='sha256:'+sha(path) or data.get('size')!=path.stat().st_size:raise RuntimeError('Uploaded asset digest/size does not match '+path.name)
        return data
    finally:connection.close()
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['status','create-repo','draft','publish'])
    parser.add_argument('--version');parser.add_argument('--commit');parser.add_argument('--notes',type=Path);parser.add_argument('--assets',type=Path,nargs='*',default=[])
    args=parser.parse_args()
    user=api('/user')
    if not user or user['login']!=OWNER:raise RuntimeError('Authenticated GitHub account does not match repository owner')
    base='/repos/'+OWNER+'/'+REPO;repo=api(base)
    if args.mode=='create-repo':
        if not repo:repo=api('/user/repos','POST',{'name':REPO,'description':'Quartz Downloader for Android — video, audio, Spotify matching and GitHub app updates.','private':False,'auto_init':False,'has_wiki':False})
        if not repo or repo['private']:raise RuntimeError('Public release repository is not available')
        print(json.dumps({'repository':repo['html_url'],'private':repo['private']}));return
    if args.mode=='status':print(json.dumps({'login':user['login'],'repository':repo['html_url'] if repo else None}));return
    if not repo or repo['private']:raise RuntimeError('Create the public repository first')
    if not args.version or not re.fullmatch(r'\d+\.\d+\.\d+',args.version):raise RuntimeError('A stable version is required')
    tag='v'+args.version;release=api(base+'/releases/tags/'+tag)
    if args.mode=='draft':
        if not args.notes or not args.commit:raise RuntimeError('--notes and --commit are required')
        if not release:release=api(base+'/releases','POST',{'tag_name':tag,'target_commitish':args.commit,'name':'Quartz Downloader '+args.version,'body':args.notes.read_text(encoding='utf-8'),'draft':True,'prerelease':False})
        if not release['draft']:raise RuntimeError('This release is already public; it will not be edited')
        for path in args.assets:
            asset=upload(release,path);print(json.dumps({'asset':asset['name'],'size':asset['size'],'digest':asset['digest']}))
        print(json.dumps({'draft':True,'release_id':release['id'],'tag':tag}));return
    if not release:raise RuntimeError('Prepare a draft release first')
    expected='Quartz-Downloader-Android-'+args.version+'.apk'
    if not any(a['name']==expected and a.get('digest','').startswith('sha256:') for a in release['assets']):raise RuntimeError('Verified APK asset is missing')
    if not any(a['name'].endswith('-Source.zip') for a in release['assets']):raise RuntimeError('Corresponding source asset is missing')
    release=api(base+'/releases/'+str(release['id']),'PATCH',{'draft':False,'make_latest':'true'})
    print(json.dumps({'release':release['html_url'],'draft':release['draft'],'tag':release['tag_name']}))
if __name__=='__main__':
    try:TOKEN=token();main()
    except Exception as error:print(str(error),file=sys.stderr);sys.exit(1)
