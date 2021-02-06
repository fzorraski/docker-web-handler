import {handleStatus, requestInitPost} from "../utils/promise-helpers.js";

const API = 'http://localhost:8080/containers/'

const filterContainer = id => containers => containers
    .flatMap(container => container.containerId)
    .filter(containerId => containerId === id);

export const containerService = {

    listAll() {
        return fetch(API + 'list')
            .then(res => handleStatus(res))
            .catch(err => {
                console.log(err);
                return Promise.reject('Could not get the list of containers');
            });
    },

    filterById(id) {
        return this.listAll()
            .then(filterContainer(id));
    },

    stopContainer(id) {
        const json = JSON.stringify({
            containerId: id
        })
        return fetch(API + 'stop', requestInitPost(json))
            .then(res => handleStatus(res))
            .finally(() => location.reload())
    },

    remove(id) {
        const json = JSON.stringify({
            containerId: id
        })
        return fetch(API + 'remove', requestInitPost(json))
            .then(res => handleStatus(res))
            .finally(() => location.reload())
    },

    startContainer(id) {
        const json = JSON.stringify({
            containerId: id
        })
        return fetch(API + 'start', requestInitPost(json))
            .then(res => handleStatus(res))
            .finally(() => location.reload())
    }
}
