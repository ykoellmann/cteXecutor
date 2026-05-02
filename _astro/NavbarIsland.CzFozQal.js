import{r as s}from"./index.CdJzaNS0.js";var h={exports:{}},d={};/**
 * @license React
 * react-jsx-runtime.production.js
 *
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */var f;function v(){if(f)return d;f=1;var e=Symbol.for("react.transitional.element"),n=Symbol.for("react.fragment");function r(a,o,l){var i=null;if(l!==void 0&&(i=""+l),o.key!==void 0&&(i=""+o.key),"key"in o){l={};for(var c in o)c!=="key"&&(l[c]=o[c])}else l=o;return o=l.ref,{$$typeof:e,type:a,key:i,ref:o!==void 0?o:null,props:l}}return d.Fragment=n,d.jsx=r,d.jsxs=r,d}var x;function j(){return x||(x=1,h.exports=v()),h.exports}var t=j();/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const y=e=>e.replace(/([a-z0-9])([A-Z])/g,"$1-$2").toLowerCase(),p=(...e)=>e.filter((n,r,a)=>!!n&&n.trim()!==""&&a.indexOf(n)===r).join(" ").trim();/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */var w={xmlns:"http://www.w3.org/2000/svg",width:24,height:24,viewBox:"0 0 24 24",fill:"none",stroke:"currentColor",strokeWidth:2,strokeLinecap:"round",strokeLinejoin:"round"};/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const E=s.forwardRef(({color:e="currentColor",size:n=24,strokeWidth:r=2,absoluteStrokeWidth:a,className:o="",children:l,iconNode:i,...c},g)=>s.createElement("svg",{ref:g,...w,width:n,height:n,stroke:e,strokeWidth:a?Number(r)*24/Number(n):r,className:p("lucide",o),...c},[...i.map(([k,b])=>s.createElement(k,b)),...Array.isArray(l)?l:[l]]));/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const m=(e,n)=>{const r=s.forwardRef(({className:a,...o},l)=>s.createElement(E,{ref:l,iconNode:n,className:p(`lucide-${y(e)}`,a),...o}));return r.displayName=`${e}`,r};/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const R=m("Github",[["path",{d:"M15 22v-4a4.8 4.8 0 0 0-1-3.5c3 0 6-2 6-5.5.08-1.25-.27-2.48-1-3.5.28-1.15.28-2.35 0-3.5 0 0-1 0-3 1.5-2.64-.5-5.36-.5-8 0C6 2 5 2 5 2c-.3 1.15-.3 2.35 0 3.5A5.403 5.403 0 0 0 4 9c0 3.5 3 5.5 6 5.5-.39.49-.68 1.05-.85 1.65-.17.6-.22 1.23-.15 1.85v4",key:"tonef"}],["path",{d:"M9 18c-4.51 2-5-2-7-2",key:"9comsn"}]]);/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const C=m("Moon",[["path",{d:"M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z",key:"a7tn18"}]]);/**
 * @license lucide-react v0.469.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const I=m("Sun",[["circle",{cx:"12",cy:"12",r:"4",key:"4exip2"}],["path",{d:"M12 2v2",key:"tus03m"}],["path",{d:"M12 20v2",key:"1lh1kg"}],["path",{d:"m4.93 4.93 1.41 1.41",key:"149t6j"}],["path",{d:"m17.66 17.66 1.41 1.41",key:"ptbguv"}],["path",{d:"M2 12h2",key:"1t8f8n"}],["path",{d:"M20 12h2",key:"1q8mjw"}],["path",{d:"m6.34 17.66-1.41 1.41",key:"1m8zz5"}],["path",{d:"m19.07 4.93-1.41 1.41",key:"1shlcs"}]]);function N(){if(typeof window>"u")return"dark";const e=localStorage.getItem("theme");return e||(window.matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light")}function M(){const[e,n]=s.useState("dark");s.useEffect(()=>{const a=N();n(a),document.documentElement.setAttribute("data-theme",a)},[]);function r(){const a=e==="dark"?"light":"dark";n(a),document.documentElement.setAttribute("data-theme",a),localStorage.setItem("theme",a)}return t.jsx("button",{className:"theme-toggle",onClick:r,"aria-label":`Switch to ${e==="dark"?"light":"dark"} mode`,children:e==="dark"?t.jsx(I,{size:13}):t.jsx(C,{size:13})})}const S=[{label:"projects",href:"#projects"},{label:"about",href:"#about"},{label:"resume",href:"#resume"},{label:"contact",href:"#contact"}],T=[{label:"Features",href:"#features"},{label:"Installation",href:"#installation"},{label:"Docs",href:"#docs"},{label:"Changelog",href:"#changelog"}];function A(e){const n=e.links??(e.kind==="homepage"?S:T);return t.jsxs(t.Fragment,{children:[t.jsxs("nav",{className:"navbar",style:{position:"sticky",top:0,zIndex:100,backdropFilter:"blur(12px)",background:"color-mix(in srgb, var(--bg-0) 85%, transparent)",height:e.kind==="homepage"?"52px":"54px"},children:[t.jsx("div",{className:"navbar-brand",children:t.jsxs("a",{href:"/",style:{display:"flex",alignItems:"center",gap:"8px",textDecoration:"none"},children:[e.icon&&t.jsx("span",{style:{display:"flex",alignItems:"center",flexShrink:0},children:e.icon}),e.kind==="homepage"?!e.icon&&t.jsx("span",{style:{fontFamily:"var(--font-mono)",fontWeight:700,fontSize:"15px",color:"var(--accent)"},children:"yk"}):t.jsxs(t.Fragment,{children:[t.jsx("span",{style:{fontFamily:"var(--font-mono)",fontWeight:700,fontSize:"14px",color:"var(--text-primary)"},children:e.productName}),t.jsx("span",{className:"navbar-domain",children:".koellmann.dev"})]})]})}),t.jsx("div",{className:"navbar-links",children:n.map(r=>t.jsx("a",{href:r.href,className:"navbar-link",children:r.label},r.href))}),t.jsxs("div",{style:{display:"flex",alignItems:"center",gap:"8px"},children:[t.jsx(M,{}),e.kind==="homepage"?t.jsx("a",{href:"https://github.com/ykoellmann",target:"_blank",rel:"noopener noreferrer",className:"btn-icon","aria-label":"GitHub",children:t.jsx(R,{size:14})}):t.jsx("a",{href:e.installHref??"#installation",className:"btn btn-primary btn-sm",children:"Install"})]})]}),t.jsx("div",{style:{height:"2px",background:"linear-gradient(90deg, var(--accent), transparent)"}})]})}const u={name:"cteXecutor",githubUrl:"https://github.com/ykoellmann/ctexecutor",marketplaceUrl:"https://plugins.jetbrains.com/plugin/27835-ctexecutor"},F=[{label:"Features",href:"#features"},{label:"Installation",href:"#installation"},{label:"Docs",href:u.githubUrl},{label:"Changelog",href:"#changelog"}],z=t.jsxs("svg",{width:"28",height:"28",viewBox:"0 0 64 64",xmlns:"http://www.w3.org/2000/svg",children:[t.jsx("text",{x:"8",y:"30",fill:"#86c98e",fontSize:"20",fontFamily:"'JetBrains Mono', 'Courier New', monospace",fontWeight:"bold",children:"WITH"}),t.jsx("polygon",{points:"42,22 56,32 42,42",fill:"#86c98e"})]});function L(){return t.jsx(A,{kind:"product",productName:u.name,icon:z,installHref:u.marketplaceUrl,links:F})}export{L as default};
