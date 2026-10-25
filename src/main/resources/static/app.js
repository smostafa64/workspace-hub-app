const byId=id=>document.getElementById(id);
let resources=[];
const escapeHtml=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, options={}) {
  const response=await fetch(path,{...options,headers:{'Content-Type':'application/json',...(options.headers||{})}});
  if (!response.ok) { const error=await response.json().catch(()=>({error:`HTTP ${response.status}`}));throw new Error(error.error||`HTTP ${response.status}`); }
  return response.json();
}
function status(message, error=false){byId('message').textContent=message;byId('message').className=error?'error':'ok';}
async function loadResources(){
  resources=await api('/api/resources');
  byId('resource-count').textContent=`${resources.filter(r=>r.active).length} available`;
  byId('resource-list').innerHTML=resources.map(r=>`<article class="resource-card" data-testid="resource-card"><span class="pill ${r.active?'':'unavailable'}">${r.active?escapeHtml(r.type):'UNAVAILABLE'}</span><strong>${escapeHtml(r.name)}</strong><small>${escapeHtml(r.building)} · Floor ${r.floorNumber??'-'} · Capacity ${r.capacity}${r.approvalRequired?' · Approval required':''}</small></article>`).join('');
  byId('resource').innerHTML=resources.filter(r=>r.active).map(r=>`<option value="${r.id}">${escapeHtml(r.name)} (${escapeHtml(r.code)})</option>`).join('');
}
async function loadReservations(){
  const bookings=await api('/api/reservations');
  const active=bookings.filter(r=>r.status==='PENDING'||r.status==='CONFIRMED');
  byId('reservations').innerHTML=active.length?active.map(r=>`<div class="row" data-testid="booking-row"><div><strong>${escapeHtml(r.title)}</strong><small>${escapeHtml(r.resourceName)} · ${new Date(r.startsAt).toLocaleString()}</small><div class="status">${r.status}</div></div><button type="button" data-cancel-id="${r.id}">Cancel</button></div>`).join(''):'<p>No upcoming or active reservations.</p>';
}
byId('reservation-form').addEventListener('submit',async event=>{
  event.preventDefault();
  const start=new Date(byId('start').value),end=new Date(byId('end').value);
  if(Number.isNaN(start.getTime())||Number.isNaN(end.getTime()))return status('Please choose valid dates.',true);
  try{
    const booking=await api('/api/reservations',{method:'POST',body:JSON.stringify({resourceId:Number(byId('resource').value),title:byId('title').value,startsAt:start.toISOString(),endsAt:end.toISOString()})});
    status(`Reservation ${booking.id} created: ${booking.status}`);byId('title').value='';await loadReservations();
  }catch(error){status(error.message,true);}
});
byId('reservations').addEventListener('click',async event=>{
  const id=event.target.getAttribute('data-cancel-id');if(!id)return;
  try{await api(`/api/reservations/${id}/cancel`,{method:'POST'});status('Reservation cancelled.');await loadReservations();}catch(error){status(error.message,true);}
});
byId('refresh').addEventListener('click',()=>loadReservations().catch(err=>status(err.message,true)));
Promise.all([loadResources(),loadReservations()]).catch(err=>status(err.message,true));
