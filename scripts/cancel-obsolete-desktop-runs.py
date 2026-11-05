"""Cancel only superseded desktop-release runs for this exact transport branch."""
import json,os,urllib.parse,urllib.request
repo=os.environ['GITHUB_REPOSITORY'];current=int(os.environ['GITHUB_RUN_ID']);branch=os.environ['GITHUB_REF_NAME']
base='https://api.github.com/repos/'+repo
headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'}
url=base+'/actions/workflows/desktop-release.yml/runs?'+urllib.parse.urlencode({'branch':branch,'per_page':100})
with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=30) as response:data=json.load(response)
for run in data['workflow_runs']:
    if run['id']==current or run['status']=='completed':continue
    try:
        with urllib.request.urlopen(urllib.request.Request(base+f"/actions/runs/{run['id']}/cancel",data=b'',headers=headers,method='POST'),timeout=30):pass
        print('Cancelled superseded desktop run',run['id'])
    except urllib.error.HTTPError as e:
        if e.code!=409:raise
