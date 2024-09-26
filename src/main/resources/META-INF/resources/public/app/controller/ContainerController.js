import {
    getContainersService,
    removeContainerService,
    startContainerService,
    stopContainerService
} from "../services/ContainerService.js";

import {showLoader} from "../views/ContainersView.js";

export function getContainers(){
    return getContainersService();
}

export function stopContainer(id) {
    if (confirm('stop  ' + id + ' ?')) {
        showLoader();
        stopContainerService(id);
    }
}

export function removeContainer(id) {
    if (confirm('Remove ' + id + ' ?')) {
        showLoader();
        removeContainerService(id);
    }
}

export function startContainer(id) {
    if (confirm('Start ' + id + ' ?')) {
        startContainerService(id).then(r => console.log(r));
    }
}

export function isUpContainer(status) {
    return !!status.includes("Up");
}

