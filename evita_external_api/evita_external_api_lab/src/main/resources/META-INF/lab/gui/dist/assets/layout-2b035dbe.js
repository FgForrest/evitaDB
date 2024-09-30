/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import{p as N,bd as V,be as oe,bf as U,ar as Z,s as X,bg as ne,bh as ae,h as s,l as se,f as ue,bi as $,bj as le,D as R,k as ie,bk as re}from"./index-dbf73d44.js";const M=Symbol.for("vuetify:layout"),q=Symbol.for("vuetify:layout-item"),K=1e3,fe=N({overlaps:{type:Array,default:()=>[]},fullHeight:Boolean},"layout"),ye=N({name:{type:String},order:{type:[Number,String],default:0},absolute:Boolean},"layout-item");function pe(){const u=V(M);if(!u)throw new Error("[Vuetify] Could not find injected layout");return{getLayoutItem:u.getLayoutItem,mainRect:u.mainRect,mainStyles:u.mainStyles}}function me(u){const l=V(M);if(!l)throw new Error("[Vuetify] Could not find injected layout");const d=u.id??`layout-item-${oe()}`,r=U("useLayoutItem");Z(q,{id:d});const n=X(!1);ne(()=>n.value=!0),ae(()=>n.value=!1);const{layoutItemStyles:c,layoutItemScrimStyles:v}=l.register(r,{...u,active:s(()=>n.value?!1:u.active.value),id:d});return se(()=>l.unregister(d)),{layoutItemStyles:c,layoutRect:l.layoutRect,layoutItemScrimStyles:v}}const ce=(u,l,d,r)=>{let n={top:0,left:0,right:0,bottom:0};const c=[{id:"",layer:{...n}}];for(const v of u){const m=l.get(v),g=d.get(v),z=r.get(v);if(!m||!g||!z)continue;const b={...n,[m.value]:parseInt(n[m.value],10)+(z.value?parseInt(g.value,10):0)};c.push({id:v,layer:b}),n=b}return c};function ge(u){const l=V(M,null),d=s(()=>l?l.rootZIndex.value-100:K),r=ue([]),n=$(new Map),c=$(new Map),v=$(new Map),m=$(new Map),g=$(new Map),{resizeRef:z,contentRect:b}=le(),F=s(()=>{const t=new Map,i=u.overlaps??[];for(const e of i.filter(a=>a.includes(":"))){const[a,o]=e.split(":");if(!r.value.includes(a)||!r.value.includes(o))continue;const f=n.get(a),p=n.get(o),S=c.get(a),w=c.get(o);!f||!p||!S||!w||(t.set(o,{position:f.value,amount:parseInt(S.value,10)}),t.set(a,{position:p.value,amount:-parseInt(w.value,10)}))}return t}),h=s(()=>{const t=[...new Set([...v.values()].map(e=>e.value))].sort((e,a)=>e-a),i=[];for(const e of t){const a=r.value.filter(o=>{var f;return((f=v.get(o))==null?void 0:f.value)===e});i.push(...a)}return ce(i,n,c,m)}),j=s(()=>!Array.from(g.values()).some(t=>t.value)),I=s(()=>h.value[h.value.length-1].layer),W=s(()=>({"--v-layout-left":R(I.value.left),"--v-layout-right":R(I.value.right),"--v-layout-top":R(I.value.top),"--v-layout-bottom":R(I.value.bottom),...j.value?void 0:{transition:"none"}})),x=s(()=>h.value.slice(1).map((t,i)=>{let{id:e}=t;const{layer:a}=h.value[i],o=c.get(e),f=n.get(e);return{id:e,...a,size:Number(o.value),position:f.value}})),k=t=>x.value.find(i=>i.id===t),O=U("createLayout"),H=X(!1);ie(()=>{H.value=!0}),Z(M,{register:(t,i)=>{let{id:e,order:a,position:o,layoutSize:f,elementSize:p,active:S,disableTransitions:w,absolute:G}=i;v.set(e,a),n.set(e,o),c.set(e,f),m.set(e,S),w&&g.set(e,w);const T=re(q,O==null?void 0:O.vnode).indexOf(t);T>-1?r.value.splice(T,0,e):r.value.push(e);const B=s(()=>x.value.findIndex(L=>L.id===e)),A=s(()=>d.value+h.value.length*2-B.value*2),J=s(()=>{const L=o.value==="left"||o.value==="right",C=o.value==="right",ee=o.value==="bottom",P=p.value??f.value,te=P===0?"%":"px",D={[o.value]:0,zIndex:A.value,transform:`translate${L?"X":"Y"}(${(S.value?0:-(P===0?100:P))*(C||ee?-1:1)}${te})`,position:G.value||d.value!==K?"absolute":"fixed",...j.value?void 0:{transition:"none"}};if(!H.value)return D;const y=x.value[B.value];if(!y)throw new Error(`[Vuetify] Could not find layout item "${e}"`);const E=F.value.get(e);return E&&(y[E.position]+=E.amount),{...D,height:L?`calc(100% - ${y.top}px - ${y.bottom}px)`:p.value?`${p.value}px`:void 0,left:C?void 0:`${y.left}px`,right:C?`${y.right}px`:void 0,top:o.value!=="bottom"?`${y.top}px`:void 0,bottom:o.value!=="top"?`${y.bottom}px`:void 0,width:L?p.value?`${p.value}px`:void 0:`calc(100% - ${y.left}px - ${y.right}px)`}}),Q=s(()=>({zIndex:A.value-1}));return{layoutItemStyles:J,layoutItemScrimStyles:Q,zIndex:A}},unregister:t=>{v.delete(t),n.delete(t),c.delete(t),m.delete(t),g.delete(t),r.value=r.value.filter(i=>i!==t)},mainRect:I,mainStyles:W,getLayoutItem:k,items:x,layoutRect:b,rootZIndex:d});const Y=s(()=>["v-layout",{"v-layout--full-height":u.fullHeight}]),_=s(()=>({zIndex:l?d.value:void 0,position:l?"relative":void 0,overflow:l?"hidden":void 0}));return{layoutClasses:Y,layoutStyles:_,getLayoutItem:k,items:x,layoutRect:b,layoutRef:z}}export{ye as a,pe as b,ge as c,fe as m,me as u};
