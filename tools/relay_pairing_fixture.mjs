#!/usr/bin/env node
/** Isolated test peer using the installed official @getpaseo/relay library. No AI or shell executes. */
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { writeFileSync, renameSync } from 'node:fs';
const cli = process.env.PASEO_CLI_PACKAGE_JSON ?? resolve(dirname(process.execPath), '../lib/node_modules/@getpaseo/cli/package.json');
const require = createRequire(cli);
const { WebSocketServer } = require('ws');
const { openDaemonSecureChannel, createDaemonChannel } = await import(pathToFileURL(require.resolve('@getpaseo/relay/e2ee')));
const { generateKeyPair, exportPublicKey } = await import(pathToFileURL(require.resolve('@getpaseo/relay')));
const keys = generateKeyPair();
const file = process.argv[2] ?? '/tmp/paseo-relay-fixture.json';
const port = Number(process.argv[3] ?? 6770);
const info = {port, links:{}, androidLinks:{}, audit:{authorizationHeaders:0,plaintextApplications:0,hellos:0}};
function persist() { writeFileSync(file+'.tmp',JSON.stringify(info)); renameSync(file+'.tmp',file); }
for (const id of ['protected','legacy','downgrade','password']) {
    for (const [field,host] of [['links','127.0.0.1'],['androidLinks','10.0.2.2']]) {
        const offer={v:2,serverId:id,daemonPublicKeyB64:exportPublicKey(keys.publicKey),relay:{endpoint:host+':'+port,useTls:false},
            ...(['protected','password'].includes(id)?{minimumSecurityMode:'protected'}:{})};
        info[field][id]='https://app.paseo.sh/#offer='+Buffer.from(JSON.stringify(offer)).toString('base64url');
    }
}
persist();
const server = new WebSocketServer({port,host:'127.0.0.1'});
server.on('connection',(ws,request)=>{
    const id=new URL(request.url,'http://localhost').searchParams.get('serverId');
    const legacy=['legacy','downgrade'].includes(id);
    if (request.headers.authorization) { info.audit.authorizationHeaders++; persist(); }
    let secured;
    const transport={send:data=>ws.send(data),close:(code=1000,reason='')=>ws.close(code,reason),onmessage:null,onclose:null,onerror:null};
    const send=(type,payload)=>secured.send(JSON.stringify({type:'session',message:{type,payload}}));
    const events={
        onmessage:data=>{
            if (typeof data!=='string') return;
            const frame=JSON.parse(data);
            if(frame.type==='hello') {
                info.audit.hellos++; persist();
                if(id==='password' && frame.auth?.password!=='test') {
                    secured.send(JSON.stringify({type:'hello.rejected',reason:frame.auth?.password?'incorrect_password':'password_required',accepts:['password']}));
                    return;
                }
                send('status',{status:'server_info',hostname:'Relay fixture',serverId:id,
                    features:{creationLifecycle:true,ownedSubscriptions:true,selectiveAgentTimeline:true,relayProtectedSecurity:!legacy || id==='downgrade'}});
                return;
            }
            const m=frame.message??{}, requestId=m.requestId;
            const workspace={id:'relay-workspace',projectId:'relay-project',projectDisplayName:'Relay fixture',projectRootPath:'/fixture',workspaceDirectory:'/fixture',projectKind:'directory',workspaceKind:'directory',name:'Relay workspace',status:'done',activityAt:null,gitRuntime:null};
            switch(m.type) {
                case 'fetch_workspaces_request': send('fetch_workspaces_response',{requestId,subscriptionId:'workspaces',entries:[workspace],emptyProjects:[],pageInfo:{hasMore:false,nextCursor:null}}); break;
                case 'fetch_agents_request': send('fetch_agents_response',{requestId,subscriptionId:'agents',entries:[],pageInfo:{hasMore:false,nextCursor:null}}); break;
                case 'list_available_providers_request': send('list_available_providers_response',{requestId,providers:[]}); break;
                case 'echo_request': send('echo_response',{requestId,text:m.text}); break;
                case 'file_explorer_request': send('file_explorer_response',{requestId,error:null,directory:{entries:[{name:'README.md',path:'README.md',kind:'file'}]},file:m.mode==='file'?{path:'README.md',kind:'text',content:'Official relay library connection',modifiedAt:'2026-10-29T00:00:00Z',revision:'fixture-v1'}:null}); break;
                case 'subscription.release.request': send('subscription.release.response',{requestId,subscriptionId:m.subscriptionId}); break;
                default: send('rpc_error',{requestId,error:'Unsupported fixture request'});
            }
        },
        onerror:()=>{},onclose:()=>{},
    };
    const promise=legacy ? createDaemonChannel(transport,keys,events) : openDaemonSecureChannel(transport,{daemonKeyPair:keys,minimumMode:'protected',events});
    promise.then(channel=>{secured=channel;}).catch(()=>{if(ws.readyState===1)ws.close(4002,'Handshake rejected');});
    ws.on('message',(raw,isBinary)=>{
        if(!isBinary) {
            try { const frame=JSON.parse(raw.toString()); if(['hello','session'].includes(frame.type)){info.audit.plaintextApplications++;persist();} }
            catch {}
        }
        transport.onmessage?.({data:isBinary?raw.buffer.slice(raw.byteOffset,raw.byteOffset+raw.byteLength):raw.toString(),isBinary});
    });
    ws.on('close',(code,reason)=>transport.onclose?.(code,reason.toString()));
    ws.on('error',error=>transport.onerror?.(error));
});
console.log('Official relay crypto fixture listening on 127.0.0.1:'+port);
