import {containerService} from "../services/container-service.js";
import {showLoader} from "../views/ContainersView.js";


export function stopContainer(id) {
    if (confirm('stop  ' + id + ' ?')) {
        console.log(id)
        showLoader();
        containerService
            .stopContainer(id);
    }
    console.log(id)
}

export function removeContainer(id) {
    if (confirm('Remove ' + id + ' ?')) {
        console.log(id)
        showLoader();
        containerService
            .remove(id);
    }
}

export function startContainer(id) {
    if (confirm('Start ' + id + ' ?')) {
        containerService
            .startContainer(id).then(r => console.log(r));
    }
}

export function isUpContainer(status) {
    console.log(status)
    return !!status.includes("Up");
}

