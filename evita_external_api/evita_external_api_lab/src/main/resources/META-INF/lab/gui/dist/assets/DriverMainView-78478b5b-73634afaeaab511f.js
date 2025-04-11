import{_ as X,a as Y,V as Z,p as ee,b as ae,C as t,M as k,c as te,W as ne,d as se}from"./ConnectionItemType-0566d1b1-73634afaeaab511f.js";import{f as R,h as oe,i as re,j as ie,k as ce,l as le,n as ue,q as de,s as ve,t as fe,v as me,x as p,y as pe,z as ye,o as l,e as y,w as v,d as f,A as x,B as N,C as D,V as be,D as ge,E as L,F as we,G as A,H as F,I as Ce,J as E,K as Te,L as M,M as _e,N as B,O as I,S as Se,T as Ve,P as he,Q as ke,R as xe,U as Ne,W as De,_ as Le}from"./index-73634afaeaab511f.js";import"./layout-6066fb81-73634afaeaab511f.js";const Ae={class:"panel-header"},Fe={class:"text-gray-light text-sm-body-2 font-weight-medium"},Be=R({__name:"DriverConnectionExplorerPanel",setup(Q){const u=oe(),b=re(),P=ie(),G=ce(),W=le(),$=ue(),J=de(),O=ve(),w=fe(),{t:c}=me(),s=b.getDriverConnection();ee(s);const r=p();ae(r);const m=p();pe([()=>s,r],async()=>m.value=await K(),{immediate:!0});const S=p(!1),U=ye(()=>m.value==null?[]:Array.from(m.value.values())),C=p(),g=p(!1),T=p(!1);let V=!1;async function z(){V||(g.value=!0,V=await h().then(a=>a?_():!1),g.value=!1)}async function j(){g.value=!0,await h().then(a=>a?_():!1),g.value=!1}async function h(){try{return r.value=await b.getServerStatus(s),!0}catch(a){return await w.error(c("explorer.connection.notification.couldNotLoadServerStatus",{connectionName:s.name,reason:a.message})),!1}}async function _(){try{return C.value=(await b.getCatalogs(s,!0)).sort((a,n)=>a.name.localeCompare(n.name)),!0}catch(a){return await w.error(c("explorer.connection.notification.couldNotLoadCatalogs",{connectionName:s.name,reason:a.message})),!1}}async function q(){try{await b.closeAllSessions(s),await w.success(c("explorer.connection.notification.closedAllSessions",{connectionName:s.name}))}catch(a){await w.error(c("explorer.connection.notification.couldNotCloseSessions",{connectionName:s.name,reason:a.message}))}}function H(a){if(m.value==null)return;const n=m.value.get(a);n instanceof B&&n.execute()}async function K(){const a=r.value!=null&&r.value.apiEnabled(I.GraphQL),n=r.value!=null&&r.value.apiEnabled(I.Observability),e=r.value!=null,d=e&&!r.value.readOnly,o=new Map;return o.set(t.Server,i(t.Server,Se.icon(),()=>u.createTab(G.createNew(s)),e)),o.set(t.Tasks,i(t.Tasks,Ve.icon(),()=>{u.createTab(W.createNew(s))},d)),o.set(t.TrafficRecordings,i(t.TrafficRecordings,he.icon(),()=>{u.createTab(J.createNew(s))},d)),o.set(t.JfrRecordings,i(t.JfrRecordings,ke.icon(),()=>{u.createTab($.createNew(s))},d&&n)),o.set(t.GraphQLSystemAPIConsole,i(t.GraphQLSystemAPIConsole,Ne.icon(),()=>u.createTab(P.createNew(s,"system",xe.System)),a)),o.set(t.ManageSubheader,new k(c("explorer.connection.subheader.manage"))),o.set(t.Refresh,i(t.Refresh,"mdi-refresh",async()=>await j())),o.set(t.CloseAllSessions,i(t.CloseAllSessions,"mdi-lan-disconnect",()=>q(),e)),o.set(t.CatalogsSubheader,new k(c("explorer.connection.subheader.catalogs"))),o.set(t.CreateCatalog,i(t.CreateCatalog,"mdi-plus",()=>T.value=!0,d)),o.set(t.CatalogBackups,i(t.CatalogBackups,De.icon(),()=>{u.createTab(O.createNew(s))},d)),o}function i(a,n,e,d=!0){return new B(a,c(`explorer.connection.actions.${a}`),n,e,void 0,!d)}return z().then(),(a,n)=>(l(),y(Z,{permanent:"",width:325,"onUpdate:modelValue":n[3]||(n[3]=e=>a.$emit("update:modelValue",e)),class:"bg-primary"},{default:v(()=>[f(F,{density:"compact",nav:""},{default:v(()=>[x("div",Ae,[x("span",Fe,N(D(c)("explorer.title")),1),f(be,{"menu-items":m.value,modelValue:S.value,"onUpdate:modelValue":n[1]||(n[1]=e=>S.value=e)},{activator:v(({props:e})=>[g.value?(l(),y(ge,L({key:0},e,{indeterminate:"",size:"16",class:"connection-loading"}),null,16)):(l(),y(we,L({key:1},e,{class:"text-gray-light"}),{default:v(()=>n[4]||(n[4]=[A(" mdi-dots-vertical ")])),_:2},1040))]),default:v(()=>[f(F,{density:"compact",items:U.value,"onClick:select":n[0]||(n[0]=e=>H(e.id))},{item:v(({props:e})=>[f(Ce,{"prepend-icon":e.prependIcon,value:e.value,disabled:e.disabled},{default:v(()=>[A(N(e.title),1)]),_:2},1032,["prepend-icon","value","disabled"])]),_:1},8,["items"])]),_:1},8,["menu-items","modelValue"])]),C.value!=null&&C.value.size>0?(l(!0),E(M,{key:0},Te(C.value,e=>(l(),y(te,{key:e.name,catalog:e,onChange:_},null,8,["catalog"]))),128)):(l(),y(X,{key:1})),T.value?(l(),y(Y,{key:2,modelValue:T.value,"onUpdate:modelValue":n[2]||(n[2]=e=>T.value=e),connection:D(s),onCreate:_},null,8,["modelValue","connection"])):_e("",!0)]),_:1})]),_:1}))}});const Ie=Le(Be,[["__scopeId","data-v-b2434a4c"]]),Qe=R({__name:"DriverMainView",setup(Q){return(u,b)=>(l(),E(M,null,[f(ne),f(Ie),f(se)],64))}});export{Qe as default};
