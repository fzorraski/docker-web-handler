import {handleStatus, requestInitPost} from "../utils/PromiseHelpers.js";
import {showLoader, stopLoader} from "../views/ContainersView.js";

const API = '/containers/'

export function getContainersService() {
    showLoader();
    return fetch(API + 'list')
        .then(res => handleStatus(res))
        .catch(err => {
            console.log(err);
            return Promise.reject('Could not get the list of containers');
        }).finally(stopLoader);
}

export function startContainerService(id){
    const json = JSON.stringify({
        containerId: id
    })

    return fetch(API + 'start', requestInitPost(json))
        .then(res => handleStatus(res))
        .finally(() => location.reload())
}

export function stopContainerService(id){
    const json = JSON.stringify({
        containerId: id
    })

    return fetch(API + 'stop', requestInitPost(json))
        .then(res => handleStatus(res))
        .finally(() => location.reload())
}

export function removeContainerService(id){
    const json = JSON.stringify({
        containerId: id
    })

    return fetch(API + 'remove', requestInitPost(json))
        .then(res => handleStatus(res))
        .finally(() => location.reload())
}