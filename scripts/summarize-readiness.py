#!/usr/bin/env python3
"""Summarize explicitly selected successful public measurement records only."""
from pathlib import Path
import argparse, collections, csv, hashlib, json, math

def distribution(values):
    assert values and all(math.isfinite(v) and v >= 0 for v in values)
    ordered=sorted(values)
    return dict(n=len(values),min_ms=min(values),max_ms=max(values),mean_ms=sum(values)/len(values),
                **{f'p{percent}_ms':ordered[math.ceil(len(values)*percent/100)-1] for percent in [50,90,95,99]})

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--run',action='append',required=True,help='public-label=directory of a successful run')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();runs=[];raw=[]
    for entry in a.run:
        label,directory=entry.split('=',1);assert label and not any(c in label for c in '/\\')
        folder=Path(directory);record=json.loads((folder/'record.json').read_text())
        assert record['completed'] is True,(label,'failed runs cannot enter success summaries')
        item=dict(label=label,layout=record['layout'],apk_sha256=record.get('app_sha256',record.get('apk_sha256')),
                  test_apk_sha256=record.get('test_sha256',record.get('test_apk_sha256')),
                  record_sha256=hashlib.sha256((folder/'record.json').read_bytes()).hexdigest(),metrics={})
        for name in ['latency','pixel-latency','soak']:
            path=folder/(name+'.json')
            if not path.exists():continue
            data=json.loads(path.read_text());groups=collections.defaultdict(list)
            item[name+'_source_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
            item.update(sdk=data['sdk'],width=data['width'],height=data['height'],debuggable=data['debuggable'],build_commit=data.get('buildCommit'))
            for sample in data['samples']:
                warmup=sample['case'].startswith('warmup-')
                raw.append(dict(run=label,case=sample['case'],warmup=warmup,ms=sample['ms'],readbacks=sample.get('readbacks'),draw_checks=sample.get('drawChecks')))
                if not warmup:groups[sample['case']].append(sample['ms'])
            item['metrics'].update({case:distribution(values) for case,values in groups.items()})
            if name=='latency':
                item['decor_overlay_caution']='Diagnostic only: overlay redraw scheduling may lag child updates; not a physical or exact visible-update latency measurement.'
                item['frames_reported']=len(data.get('frames',[]))
                item['frame_reports_dropped']=sum(x['droppedReports'] for x in data.get('frames',[]))
                durations=[x['totalNs']/1e6 for x in data.get('frames',[]) if x['totalNs']>=0 and x['firstDraw']==0]
                if durations:item['window_frame_duration']=distribution(durations)
            if name=='soak':item['soak']=dict(rounds=data['rounds'],elapsed_seconds=data['elapsedSeconds'],memory=data['memory'])
        if 'observations' in record:
            groups=collections.defaultdict(list)
            for observation in record['observations']:
                if observation['warmup']:continue
                for field in ['total_ms','wait_ms','host_command_ms']:
                    value=observation[field]
                    if value is not None:groups[observation['mode']+'_'+field].append(value)
            item['metrics']={key:distribution(values) for key,values in groups.items()}
            item['launch_states']=dict(collections.Counter(x['launch_state'] for x in record['observations'] if not x['warmup']))
        runs.append(item)
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'metrics.json').write_text(json.dumps(dict(percentile_method='nearest rank; warmups excluded; no outlier removal',runs=runs),indent=2)+'\n')
    with (a.output/'interaction-samples.csv').open('w') as file:
        w=csv.DictWriter(file,fieldnames=['run','case','warmup','ms','readbacks','draw_checks']);w.writeheader();w.writerows(raw)
    print(json.dumps({'runs':len(runs),'raw_interaction_samples':len(raw)}))

if __name__=='__main__':main()
